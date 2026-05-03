/*
 * Copyright (C) 2016-2021 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 */

package org.isoron.uhabits.activities.habits.list

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat.checkSelfPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.appcompat.app.AlertDialog
import android.widget.EditText
import android.text.InputType
import org.isoron.uhabits.activities.habits.list.tabs.PrivateTabAuthManager
import org.isoron.uhabits.activities.habits.list.tabs.TabBarView
import org.isoron.uhabits.activities.habits.list.tabs.TabManager
import org.isoron.uhabits.BaseExceptionHandler
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.activities.habits.list.views.HabitCardListAdapter
import org.isoron.uhabits.core.models.Timestamp
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.TaskRunner
import org.isoron.uhabits.core.ui.ThemeSwitcher.Companion.THEME_DARK
import org.isoron.uhabits.core.utils.MidnightTimer
import org.isoron.uhabits.database.AutoBackup
import org.isoron.uhabits.inject.ActivityContextModule
import org.isoron.uhabits.inject.DaggerHabitsActivityComponent
import org.isoron.uhabits.inject.HabitsActivityComponent
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.utils.applyRootViewInsets
import org.isoron.uhabits.utils.dismissCurrentDialog
import org.isoron.uhabits.utils.restartWithFade

class ListHabitsActivity : AppCompatActivity(), Preferences.Listener, CommandRunner.Listener {

    var pureBlack: Boolean = false
    lateinit var appComponent: HabitsApplicationComponent
    lateinit var component: HabitsActivityComponent
    lateinit var taskRunner: TaskRunner
    lateinit var adapter: HabitCardListAdapter
    lateinit var rootView: ListHabitsRootView
    lateinit var screen: ListHabitsScreen
    lateinit var prefs: Preferences
    lateinit var midnightTimer: MidnightTimer
    lateinit var tabManager: TabManager
    private lateinit var authManager: PrivateTabAuthManager

    /** Persisted across process death in a dedicated SharedPreferences file. */
    private val privateTabPrefs: SharedPreferences by lazy {
        getSharedPreferences("private_tab_state", Context.MODE_PRIVATE)
    }

    /**
     * The tab id the user was on before entering a private tab.
     * Null means the "All" tab. Persisted so we can recover after process death.
     */
    private var lastNonPrivateTabId: String? = null

    /**
     * True when the user left the app while a private tab was active.
     * Set in [onStop], cleared in [onStart] after handling re-auth.
     */
    private var requireReAuthOnStart: Boolean = false

    /** Snapshot of the highest habit/group id seen at [onPause]. */
    private var lastKnownMaxId: Long = Long.MIN_VALUE

    private val scope = CoroutineScope(Dispatchers.Main)

    private var permissionAlreadyRequested = false
    private val permissionLauncher =
        registerForActivityResult(RequestPermission()) { isGranted: Boolean ->
            if (isGranted) scheduleReminders()
            else Log.i("ListHabitsActivity", "POST_NOTIFICATIONS denied")
        }

    private lateinit var menu: ListHabitsMenu

    override fun onQuestionMarksChanged() {
        invalidateOptionsMenu()
        menu.behavior.onPreferencesChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appComponent = (applicationContext as HabitsApplication).component
        component = DaggerHabitsActivityComponent
            .builder()
            .activityContextModule(ActivityContextModule(this))
            .habitsApplicationComponent(appComponent)
            .build()
        component.themeSwitcher.apply()

        prefs       = appComponent.preferences
        prefs.addListener(this)
        pureBlack   = prefs.isPureBlackEnabled
        midnightTimer = appComponent.midnightTimer
        rootView    = component.listHabitsRootView
        screen      = component.listHabitsScreen
        adapter     = component.habitCardListAdapter
        taskRunner  = appComponent.taskRunner
        menu        = component.listHabitsMenu
        Thread.setDefaultUncaughtExceptionHandler(BaseExceptionHandler(this))
        component.listHabitsBehavior.onStartup()
        rootView.applyRootViewInsets()

        // ---- Tab feature ----
        tabManager  = TabManager(this)
        authManager = PrivateTabAuthManager(this, tabManager)
        screen.tabManager = tabManager
        screen.onImportSuccess = {
            rootView.tabBar.setTabs(tabManager.getAllTabs())
            val restoredActiveTabId = tabManager.loadActiveTab()
            rootView.tabBar.setSelectedTab(restoredActiveTabId)
            component.listHabitsSelectionMenu.isOnCustomTab = (restoredActiveTabId != null)
            adapter.activeTabId = restoredActiveTabId
            adapter.showTabDot  = (restoredActiveTabId == null)
            syncPrivateTabIds()
        }
        setupTabBar()
        setupMoveToTabCallback()
        syncPrivateTabIds()          // initial wiring so All-tab hides private items immediately
        lastKnownMaxId = maxOf(
            appComponent.habitList.maxByOrNull { it.id ?: Long.MIN_VALUE }?.id ?: Long.MIN_VALUE,
            appComponent.habitGroupList.maxByOrNull { it.id ?: Long.MIN_VALUE }?.id ?: Long.MIN_VALUE
        )
        // ---------------------

        setContentView(rootView)
    }

    // -----------------------------------------------------------------------
    // Lifecycle — private tab re-auth on resume
    // -----------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        // Recover "was private tab active when the user left?" flag from SharedPreferences
        // (survives process death, unlike in-memory fields)
        requireReAuthOnStart = privateTabPrefs.getBoolean(PREF_WAS_PRIVATE_ACTIVE, false)
        if (requireReAuthOnStart) {
            privateTabPrefs.edit().putBoolean(PREF_WAS_PRIVATE_ACTIVE, false).apply()
        }
    }

    override fun onStop() {
        val currentTabId = adapter.activeTabId
        val currentTab   = if (currentTabId != null) tabManager.getTab(currentTabId) else null
        val isPrivateNow = currentTab?.isPrivate == true

        if (isPrivateNow) {
            // Persist the flag so the re-auth check survives process death
            privateTabPrefs.edit().putBoolean(PREF_WAS_PRIVATE_ACTIVE, true).apply()
            // Disable stealth here so the recents thumbnail is already hidden by FLAG_SECURE
        }
        super.onStop()
    }

    override fun onPause() {
        midnightTimer.onPause()
        screen.onDetached()
        appComponent.commandRunner.removeListener(this)
        adapter.cancelRefresh()
        dismissCurrentDialog()
        lastKnownMaxId = maxOf(
            appComponent.habitList.maxByOrNull { it.id ?: Long.MIN_VALUE }?.id ?: Long.MIN_VALUE,
            appComponent.habitGroupList.maxByOrNull { it.id ?: Long.MIN_VALUE }?.id ?: Long.MIN_VALUE
        )
        super.onPause()
    }

    override fun onResume() {
        adapter.refresh()
        screen.onAttached()
        appComponent.commandRunner.addListener(this)
        rootView.postInvalidate()
        midnightTimer.onResume()

        assignNewItemsToActiveTab()

        // ---- Private tab re-auth on return ----
        if (requireReAuthOnStart) {
            requireReAuthOnStart = false
            handlePrivateTabReAuth()
        }

        if (appComponent.reminderScheduler.hasHabitsWithReminders()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                scheduleReminders()
            } else {
                if (checkSelfPermission(this, POST_NOTIFICATIONS) == PERMISSION_GRANTED) {
                    scheduleReminders()
                } else if (!permissionAlreadyRequested) {
                    Log.i("ListHabitsActivity", "Requesting permission: POST_NOTIFICATIONS")
                    permissionLauncher.launch(POST_NOTIFICATIONS)
                    permissionAlreadyRequested = true
                }
            }
        }

        taskRunner.run {
            try {
                AutoBackup(this@ListHabitsActivity).run()
                appComponent.widgetUpdater.updateWidgets()
            } catch (e: Exception) {
                Log.e("ListHabitActivity", "TaskRunner failed", e)
            }
        }

        if (prefs.theme == THEME_DARK && prefs.isPureBlackEnabled != pureBlack) {
            restartWithFade(ListHabitsActivity::class.java)
        }
        parseIntents()
        super.onResume()
    }

    // -----------------------------------------------------------------------
    // Private tab: re-auth after the user returns from background
    // -----------------------------------------------------------------------

    /**
     * Called on [onResume] when the app was backgrounded with a private tab active.
     *
     * Immediately falls back to the last non-private tab so no private content
     * is visible while the auth dialog is shown. Then offers re-auth:
     * - Success → switch back to private tab + stealth mode.
     * - Failure → stay on the fallback tab (stealth already disabled).
     */
    private fun handlePrivateTabReAuth() {
        val privateTabId = tabManager.loadActiveTab()
        val fallbackTabId = tabManager.loadLastNonPrivateTab()

        // Switch away from private immediately — don't show private content before auth
        switchToTab(fallbackTabId, stealth = false)
        // Also revoke the All-tab unlock — user must re-auth if they go back to "All"
        adapter.unlockedPrivateTabIds = emptySet()

        // Try to re-auth; on success go back to the private tab
        if (privateTabId != null && tabManager.getTab(privateTabId)?.isPrivate == true) {
            authManager.authenticate(
                onSuccess = {
                    switchToTab(privateTabId, stealth = true)
                    tabManager.saveActiveTab(privateTabId)
                },
                onFailure = {
                    // Stay on fallback tab — stealth already disabled
                    tabManager.saveActiveTab(fallbackTabId)
                }
            )
        }
    }

    // -----------------------------------------------------------------------
    // Tab bar setup
    // -----------------------------------------------------------------------

    private fun setupTabBar() {
        rootView.tabBar.setTabs(tabManager.getAllTabs())

        val restoredTabId = tabManager.loadActiveTab()
        val restoredTab   = if (restoredTabId != null) tabManager.getTab(restoredTabId) else null

        if (restoredTabId != null && restoredTab?.isPrivate != true) {
            // Restore a normal (non-private) tab immediately
            rootView.tabBar.setSelectedTab(restoredTabId)
            component.listHabitsSelectionMenu.isOnCustomTab = true
            adapter.activeTabId  = restoredTabId
            adapter.showTabDot   = false
        } else {
            // "All" tab as safe default (private tabs need auth before display)
            adapter.showTabDot = true
            // Defer All-tab auth until after the listener is wired up (post to message queue)
            rootView.post { handleAllTabPrivateAuth() }
        }

        rootView.tabBar.listener = object : TabBarView.Listener {

            // ------ Normal tab selection ------
            override fun onTabSelected(tabId: String?) {
                // When leaving a private tab, turn off stealth
                val wasPrivate = adapter.activeTabId?.let { tabManager.getTab(it)?.isPrivate } == true
                if (wasPrivate) authManager.disableStealthMode()

                tabManager.saveActiveTab(tabId)
                component.listHabitsSelectionMenu.isOnCustomTab = (tabId != null)
                adapter.activeTabId = tabId
                adapter.showTabDot  = (tabId == null)

                if (tabId == null) {
                    // Switched to "All" tab — ask for auth if private tabs exist
                    handleAllTabPrivateAuth()
                } else {
                    // Left "All" tab — revoke the All-tab unlock
                    adapter.unlockedPrivateTabIds = emptySet()
                }
            }

            // ------ Private tab tapped — auth gate ------
            override fun onPrivateTabRequested(tabId: String) {
                // Save where we currently are before entering the private tab
                lastNonPrivateTabId = adapter.activeTabId
                tabManager.saveLastNonPrivateTab(lastNonPrivateTabId)

                authManager.authenticate(
                    onSuccess = {
                        switchToTab(tabId, stealth = true)
                        tabManager.saveActiveTab(tabId)
                    },
                    onFailure = {
                        // Stay on current tab — do nothing
                    }
                )
            }

            // ------ Tab created ------
            override fun onTabCreated(name: String, isPrivate: Boolean) {
                if (isPrivate) {
                    createPrivateTab(name)
                } else {
                    val tab = tabManager.addTab(name, isPrivate = false)
                    rootView.tabBar.setTabs(tabManager.getAllTabs())
                    rootView.tabBar.setSelectedTab(tab.id)
                    tabManager.saveActiveTab(tab.id)
                    component.listHabitsSelectionMenu.isOnCustomTab = true
                    adapter.activeTabId = tab.id
                    adapter.showTabDot  = false
                }
            }

            // ------ Rename ------
            override fun onTabRenamed(tabId: String, newName: String) {
                tabManager.renameTab(tabId, newName)
                rootView.tabBar.setTabs(tabManager.getAllTabs())
            }

            // ------ Delete ------
            override fun onTabDeleted(tabId: String) {
                val wasPrivate = tabManager.getTab(tabId)?.isPrivate == true
                clearTabFromItems(tabId)
                tabManager.deleteTab(tabId)
                if (wasPrivate) authManager.disableStealthMode()
                component.listHabitsSelectionMenu.isOnCustomTab = false
                adapter.activeTabId = null
                adapter.showTabDot  = true
                adapter.unlockedPrivateTabIds = emptySet()
                syncPrivateTabIds()
                rootView.tabBar.setTabs(tabManager.getAllTabs())
            }

            // ------ Toggle private flag ------
            override fun onPrivacyToggled(tabId: String, makePrivate: Boolean) {
                if (makePrivate) {
                    // Becoming private — needs PIN
                    ensurePinAndMakePrivate(tabId)
                } else {
                    // Becoming public — just clear the flag, disable stealth if active
                    tabManager.setTabPrivate(tabId, false)
                    if (adapter.activeTabId == tabId) authManager.disableStealthMode()
                    adapter.unlockedPrivateTabIds = adapter.unlockedPrivateTabIds - tabId
                    syncPrivateTabIds()
                    rootView.tabBar.setTabs(tabManager.getAllTabs())
                }
                // If no private tabs remain, clear the stored PIN
                if (!tabManager.hasAnyPrivateTab()) tabManager.clearPin()
            }

            // ------ Change PIN ------
            override fun onChangePinRequested(tabId: String) {
                authManager.showPinSetup(
                    onPinSet = { newPin ->
                        tabManager.savePin(newPin)
                    }
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Private-tab creation flow
    // -----------------------------------------------------------------------

    /**
     * Creates a private tab named [name].
     *
     * If a PIN already exists (from another private tab) we reuse it and
     * switch to the new tab immediately after auth.
     * If no PIN exists yet, the PIN setup dialog is shown first.
     */
    private fun createPrivateTab(name: String) {
        val onTabReady = { tabId: String ->
            rootView.tabBar.setTabs(tabManager.getAllTabs())
            // Auth required to enter the new private tab
            lastNonPrivateTabId = adapter.activeTabId
            tabManager.saveLastNonPrivateTab(lastNonPrivateTabId)
            authManager.authenticate(
                onSuccess = {
                    switchToTab(tabId, stealth = true)
                    tabManager.saveActiveTab(tabId)
                },
                onFailure = { /* stay on current tab */ }
            )
        }

        if (tabManager.hasPin()) {
            // Reuse existing PIN — just create the tab
            val tab = tabManager.addTab(name, isPrivate = true)
            onTabReady(tab.id)
        } else {
            // No PIN yet — set one first, then create the tab
            authManager.showPinSetup(
                onPinSet = { pin ->
                    tabManager.savePin(pin)
                    val tab = tabManager.addTab(name, isPrivate = true)
                    onTabReady(tab.id)
                },
                onCancel = { /* user cancelled — don't create tab */ }
            )
        }
    }

    /**
     * Makes an existing tab private.
     * Reuses the PIN if one exists; otherwise prompts for setup.
     */
    private fun ensurePinAndMakePrivate(tabId: String) {
        val applyPrivate = {
            tabManager.setTabPrivate(tabId, true)
            syncPrivateTabIds()
            // Revoke All-tab unlock — user must re-auth to see the newly private items
            if (adapter.activeTabId == null) adapter.unlockedPrivateTabIds = emptySet()
            rootView.tabBar.setTabs(tabManager.getAllTabs())
        }

        if (tabManager.hasPin()) {
            applyPrivate()
        } else {
            authManager.showPinSetup(
                onPinSet = { pin ->
                    tabManager.savePin(pin)
                    applyPrivate()
                },
                onCancel = { /* user cancelled — don't make private */ }
            )
        }
    }

    // -----------------------------------------------------------------------
    // Tab switching helper
    // -----------------------------------------------------------------------

    /**
     * Switches the visible tab to [tabId] (null = "All") and optionally
     * enables/disables stealth mode (FLAG_SECURE) for the activity window.
     */
    private fun switchToTab(tabId: String?, stealth: Boolean) {
        if (stealth) authManager.enableStealthMode() else authManager.disableStealthMode()
        rootView.tabBar.setSelectedTab(tabId)
        component.listHabitsSelectionMenu.isOnCustomTab = (tabId != null)
        adapter.activeTabId = tabId
        adapter.showTabDot  = (tabId == null)
    }

    /**
     * Syncs [HabitCardListAdapter.privateTabIds] with the current DB state.
     * Call after any tab create/delete/privacy-change operation.
     */
    private fun syncPrivateTabIds() {
        adapter.privateTabIds = tabManager.getAllTabs()
            .filter { it.isPrivate }
            .map { it.id }
            .toSet()
    }

    /**
     * Called whenever the user lands on the "All" tab.
     *
     * If there are private tabs:
     * - Shows biometric → PIN auth.
     * - **Success**: unlocks all private tab IDs → their habits appear with a purple dot.
     * - **Cancel / failure**: [unlockedPrivateTabIds] stays empty → private items hidden.
     *
     * If there are no private tabs, does nothing.
     */
    private fun handleAllTabPrivateAuth() {
        val privateIds = tabManager.getAllTabs().filter { it.isPrivate }.map { it.id }.toSet()
        if (privateIds.isEmpty()) {
            adapter.unlockedPrivateTabIds = emptySet()
            return
        }
        authManager.authenticate(
            onSuccess = {
                adapter.unlockedPrivateTabIds = privateIds
            },
            onFailure = {
                adapter.unlockedPrivateTabIds = emptySet()
            }
        )
    }

    // -----------------------------------------------------------------------
    // CommandRunner.Listener — tab assignment for new items
    // -----------------------------------------------------------------------

    private fun assignNewItemsToActiveTab() {
        val targetTabId = tabManager.loadActiveTab()
            ?: tabManager.getDefaultTabId()
            ?: return

        var assigned = false

        val newGroups = appComponent.habitGroupList
            .filter { (it.id ?: Long.MIN_VALUE) > lastKnownMaxId }
        newGroups.forEach { group ->
            group.tabId = targetTabId
            val children = group.habitList.toList()
            children.forEach { child -> child.tabId = targetTabId }
            appComponent.habitGroupList.update(listOf(group))
            if (children.isNotEmpty()) appComponent.habitList.update(children)
            assigned = true
        }

        val newHabits = appComponent.habitList
            .filter { (it.id ?: Long.MIN_VALUE) > lastKnownMaxId }
        newHabits.forEach { habit ->
            val parentGroup = if (habit.groupId != null)
                appComponent.habitGroupList.getById(habit.groupId!!)
            else null
            habit.tabId = parentGroup?.tabId ?: targetTabId
            appComponent.habitList.update(listOf(habit))
            assigned = true
        }

        if (assigned) {
            adapter.activeTabId = adapter.activeTabId
        }
    }

    override fun onCommandFinished(command: org.isoron.uhabits.core.commands.Command) {
        // Intentionally empty — new-item tab assignment is handled in onResume
    }

    // -----------------------------------------------------------------------
    // Move-to-tab
    // -----------------------------------------------------------------------

    private fun setupMoveToTabCallback() {
        component.listHabitsSelectionMenu.moveToTabCallback = { ids ->
            showMoveToTabDialog(ids)
        }
        component.listHabitsSelectionMenu.removeFromTabCallback = { ids ->
            setTabIdOnItems(expandWithChildren(ids), null)
            adapter.activeTabId = adapter.activeTabId
        }
    }

    private fun expandWithChildren(ids: List<Long>): List<Long> {
        val result = ids.toMutableList()
        ids.forEach { id ->
            val group = appComponent.habitGroupList.getById(id)
            group?.habitList?.forEach { child ->
                child.id?.let { childId -> if (!result.contains(childId)) result.add(childId) }
            }
        }
        return result
    }

    private fun showMoveToTabDialog(ids: List<Long>) {
        val tabs   = tabManager.getAllTabs()
        val allIds = expandWithChildren(ids)

        if (tabs.isEmpty()) {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                hint = getString(R.string.tab_name_hint)
                val p = (16 * resources.displayMetrics.density).toInt()
                setPadding(p, p / 2, p, p / 2)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.tab_create_title))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        val tab = tabManager.addTab(name)
                        performMove(allIds, tab.id)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            val labels = tabs.map { it.name }.toMutableList()
            labels.add(getString(R.string.tab_create_title) + "…")

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.add_to_tab))
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which < tabs.size) {
                        performMove(allIds, tabs[which].id)
                    } else {
                        val input = EditText(this).apply {
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                            hint = getString(R.string.tab_name_hint)
                            val p = (16 * resources.displayMetrics.density).toInt()
                            setPadding(p, p / 2, p, p / 2)
                        }
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.tab_create_title))
                            .setView(input)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                val name = input.text.toString().trim()
                                if (name.isNotEmpty()) {
                                    val tab = tabManager.addTab(name)
                                    performMove(allIds, tab.id)
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
                .show()
        }
    }

    private fun performMove(ids: List<Long>, tabId: String) {
        setTabIdOnItems(expandWithChildren(ids), tabId)
        refreshTabBarAndFilter(tabId)
    }

    private fun refreshTabBarAndFilter(tabId: String) {
        rootView.tabBar.setTabs(tabManager.getAllTabs())
        rootView.tabBar.setSelectedTab(tabId)
        tabManager.saveActiveTab(tabId)
        component.listHabitsSelectionMenu.isOnCustomTab = true
        adapter.activeTabId = tabId
    }

    private fun setTabIdOnItems(ids: List<Long>, tabId: String?) {
        ids.forEach { id ->
            val habit = appComponent.habitList.getById(id)
            if (habit != null) {
                habit.tabId = tabId
                appComponent.habitList.update(listOf(habit))
            } else {
                val group = appComponent.habitGroupList.getById(id)
                if (group != null) {
                    group.tabId = tabId
                    appComponent.habitGroupList.update(listOf(group))
                    val children = group.habitList.toList()
                    children.forEach { it.tabId = tabId }
                    if (children.isNotEmpty()) appComponent.habitList.update(children)
                }
            }
        }
    }

    private fun clearTabFromItems(tabId: String) {
        val habitsToUpdate = appComponent.habitList.filter { it.tabId == tabId }
        habitsToUpdate.forEach { it.tabId = null }
        if (habitsToUpdate.isNotEmpty()) appComponent.habitList.update(habitsToUpdate)

        val groupsToUpdate = appComponent.habitGroupList.filter { it.tabId == tabId }
        groupsToUpdate.forEach { it.tabId = null }
        if (groupsToUpdate.isNotEmpty()) appComponent.habitGroupList.update(groupsToUpdate)
    }

    // -----------------------------------------------------------------------
    // Menu
    // -----------------------------------------------------------------------

    override fun onCreateOptionsMenu(m: Menu): Boolean {
        menu.onCreate(menuInflater, m)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        invalidateOptionsMenu()
        return menu.onItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        screen.onResult(request, result, data)
    }

    private fun scheduleReminders() {
        appComponent.reminderScheduler.scheduleAll()
    }

    private fun parseIntents() {
        if (intent == null) return
        if (intent.action == ACTION_EDIT) {
            val habitId   = intent.extras?.getLong("habit")
            val timestamp = intent.extras?.getLong("timestamp")
            if (habitId != null && timestamp != null) {
                val habit = appComponent.habitList.getById(habitId)
                    ?: appComponent.habitGroupList.getHabitByID(habitId)!!
                component.listHabitsBehavior.onEdit(habit, Timestamp(timestamp))
            }
        }
        intent.getLongExtra("CLEAR_NOTIFICATION_HABIT_ID", -1).takeIf { it != -1L }?.let { id ->
            val dismissHabit = appComponent.habitList.getById(id)
                ?: appComponent.habitGroupList.getHabitByID(id)
            if (dismissHabit != null) {
                appComponent.reminderController.onDismiss(dismissHabit)
            } else {
                val dismissHabitGroup = appComponent.habitGroupList.getById(id)!!
                appComponent.reminderController.onDismiss(dismissHabitGroup)
            }
        }
        intent = null
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    companion object {
        const val ACTION_EDIT = "org.isoron.uhabits.ACTION_EDIT"

        /** SharedPreferences key: was a private tab active when the user left? */
        private const val PREF_WAS_PRIVATE_ACTIVE = "was_private_tab_active"
    }
}
