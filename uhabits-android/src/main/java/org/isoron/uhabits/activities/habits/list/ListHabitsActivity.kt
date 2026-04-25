/*
 * Copyright (C) 2016-2021 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.activities.habits.list

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Intent
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

    /**
     * Snapshot of the highest habit/group id seen at [onPause].
     * On [onResume] any id higher than this was created while we were paused
     * (i.e. the user was in EditHabitActivity) and needs to be assigned to the
     * active tab.
     */
    private var lastKnownMaxId: Long = Long.MIN_VALUE

    private val scope = CoroutineScope(Dispatchers.Main)

    private var permissionAlreadyRequested = false
    private val permissionLauncher =
        registerForActivityResult(RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                scheduleReminders()
            } else {
                Log.i("ListHabitsActivity", "POST_NOTIFICATIONS denied")
            }
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

        prefs = appComponent.preferences
        prefs.addListener(this)
        pureBlack = prefs.isPureBlackEnabled
        midnightTimer = appComponent.midnightTimer
        rootView = component.listHabitsRootView
        screen = component.listHabitsScreen
        adapter = component.habitCardListAdapter
        taskRunner = appComponent.taskRunner
        menu = component.listHabitsMenu
        Thread.setDefaultUncaughtExceptionHandler(BaseExceptionHandler(this))
        component.listHabitsBehavior.onStartup()
        rootView.applyRootViewInsets()

        // ---- Tab feature ----
        tabManager = TabManager(this)
        setupTabBar()
        setupMoveToTabCallback()
        // ---------------------

        setContentView(rootView)
    }

    override fun onPause() {
        midnightTimer.onPause()
        screen.onDetached()
        appComponent.commandRunner.removeListener(this)
        adapter.cancelRefresh()
        dismissCurrentDialog()
        // Snapshot the highest id across all habits and groups so onResume can
        // detect items that were created while this activity was paused.
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

        // Assign any items created while we were paused (e.g. in EditHabitActivity)
        assignNewItemsToActiveTab()

        if (appComponent.reminderScheduler.hasHabitsWithReminders()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                scheduleReminders()
            } else {
                if (checkSelfPermission(this, POST_NOTIFICATIONS) == PERMISSION_GRANTED) {
                    scheduleReminders()
                } else {
                    // If we have not requested the permission yet, request it. Otherwide do
                    // nothing. This check is necessary to avoid an infinite onResume loop in case
                    // the user denies the permission.
                    if (!permissionAlreadyRequested) {
                        Log.i("ListHabitsActivity", "Requestion permission: POST_NOTIFICATIONS")
                        permissionLauncher.launch(POST_NOTIFICATIONS)
                        permissionAlreadyRequested = true
                    }
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

    private fun scheduleReminders() {
        appComponent.reminderScheduler.scheduleAll()
    }

    // -----------------------------------------------------------------------
    // CommandRunner.Listener — auto-link new items to active tab
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // CommandRunner.Listener — tab assignment for new items
    // -----------------------------------------------------------------------

    /**
     * Scans for habits and groups whose id exceeds [lastKnownMaxId] (meaning
     * they were created while [ListHabitsActivity] was paused, e.g. inside
     * [EditHabitActivity]) and assigns them to the active tab.
     *
     * Also handles the edge case where the user creates an item while already
     * on this activity (id snapshotting keeps this idempotent — a newly
     * inserted item always has the highest id).
     *
     * Called from [onResume] so it always runs after returning from any
     * creation flow regardless of which Activity hosted it.
     */
    private fun assignNewItemsToActiveTab() {
        val targetTabId = tabManager.loadActiveTab()
            ?: tabManager.getDefaultTabId()
            ?: return  // No tabs exist yet — nothing to assign

        var assigned = false

        // New standalone habits
        appComponent.habitList
            .filter { (it.id ?: Long.MIN_VALUE) > lastKnownMaxId }
            .forEach { habit ->
                habit.id?.let { id ->
                    tabManager.moveItemToTab(id, targetTabId)
                    assigned = true
                }
            }

        // New groups (and their child habits — children always belong to same tab as parent)
        appComponent.habitGroupList
            .filter { (it.id ?: Long.MIN_VALUE) > lastKnownMaxId }
            .forEach { group ->
                group.id?.let { groupId ->
                    tabManager.moveItemToTab(groupId, targetTabId)
                    group.habitList.forEach { child ->
                        child.id?.let { tabManager.moveItemToTab(it, targetTabId) }
                    }
                    assigned = true
                }
            }

        // Also catch child habits added to an existing group that is already on a tab.
        // The child habit is newly created (id > lastKnownMaxId) but its parent group
        // already has a tab — link the child to the same tab.
        if (!assigned) {
            appComponent.habitList
                .filter { (it.id ?: Long.MIN_VALUE) > lastKnownMaxId }
                .forEach { habit ->
                    habit.id?.let { id ->
                        // Find the parent group's tab
                        val parentGroup = appComponent.habitGroupList
                            .firstOrNull { g -> g.habitList.any { h -> h.id == id } }
                        val parentTabId = parentGroup?.id?.let { tabManager.getItemTabId(it) }
                        val dest = parentTabId ?: targetTabId
                        tabManager.moveItemToTab(id, dest)
                        assigned = true
                    }
                }
        }

        if (assigned) {
            // Refresh the adapter filter so newly assigned items appear (or stay hidden)
            val activeTabId = tabManager.loadActiveTab()
            adapter.tabFilter = if (activeTabId == null) null
            else tabManager.getTab(activeTabId)?.habitIds?.toSet()
        }
    }

    /**
     * [onCommandFinished] fires while [ListHabitsActivity] is active (not paused).
     * For create-habit/group commands this is a no-op here because the create
     * dialog always opens a new Activity, pausing this one — so the command
     * fires while we are NOT listening.  All assignment is handled by
     * [assignNewItemsToActiveTab] in [onResume].
     *
     * We keep the override to satisfy [CommandRunner.Listener].
     */
    override fun onCommandFinished(command: org.isoron.uhabits.core.commands.Command) {
        // Intentionally empty — new-item tab assignment is handled in onResume
        // via assignNewItemsToActiveTab() which is immune to the pause/resume gap.
    }

    // -----------------------------------------------------------------------
    // Tab bar
    // -----------------------------------------------------------------------

    /**
     * Binds [TabBarView.listener] on [rootView.tabBar] to [tabManager].
     * Restores the previously active tab so the user lands on the same tab
     * after an app restart.  Called once in [onCreate].
     */
    private fun setupTabBar() {
        rootView.tabBar.setTabs(tabManager.getAllTabs())

        // ---- Restore last active tab ----
        val restoredTabId = tabManager.loadActiveTab()
        if (restoredTabId != null) {
            rootView.tabBar.setSelectedTab(restoredTabId)
            component.listHabitsSelectionMenu.isOnCustomTab = true
            adapter.tabFilter = tabManager.getTab(restoredTabId)?.habitIds?.toSet()
        }
        // (if null, bar already defaults to "All" with no filter – nothing to do)

        rootView.tabBar.listener = object : org.isoron.uhabits.activities.habits.list.tabs.TabBarView.Listener {

            override fun onTabSelected(tabId: String?) {
                tabManager.saveActiveTab(tabId)
                component.listHabitsSelectionMenu.isOnCustomTab = (tabId != null)
                adapter.tabFilter = if (tabId == null) null
                else tabManager.getTab(tabId)?.habitIds?.toSet()
            }

            override fun onTabCreated(name: String) {
                val tab = tabManager.addTab(name)
                rootView.tabBar.setTabs(tabManager.getAllTabs())
                rootView.tabBar.setSelectedTab(tab.id)
                tabManager.saveActiveTab(tab.id)
                component.listHabitsSelectionMenu.isOnCustomTab = true
                adapter.tabFilter = tab.habitIds.toSet()
            }

            override fun onTabRenamed(tabId: String, newName: String) {
                tabManager.renameTab(tabId, newName)
                rootView.tabBar.setTabs(tabManager.getAllTabs())
            }

            override fun onTabDeleted(tabId: String) {
                tabManager.deleteTab(tabId)
                tabManager.saveActiveTab(null)
                component.listHabitsSelectionMenu.isOnCustomTab = false
                adapter.tabFilter = null
                rootView.tabBar.setTabs(tabManager.getAllTabs())
            }
        }
    }

    /**
     * Shows a dialog letting the user pick (or create) a tab, then adds every
     * selected habit to that tab.
     *
     * The callback is invoked from [ListHabitsSelectionMenu] when the user
     * taps "Add to tab" in the contextual action bar.
     */
    private fun setupMoveToTabCallback() {
        component.listHabitsSelectionMenu.moveToTabCallback = { ids ->
            showMoveToTabDialog(ids)
        }
        component.listHabitsSelectionMenu.removeFromTabCallback = { ids ->
            val activeTabId = tabManager.loadActiveTab()
            if (activeTabId != null) {
                // Expand groups: also unassign their child habits
                val allIds = expandWithChildren(ids)
                allIds.forEach { tabManager.removeItemFromTab(it) }
                adapter.tabFilter = tabManager.getTab(activeTabId)?.habitIds?.toSet()
            }
        }
    }

    /**
     * Given a list of selected ids (habits and/or groups), returns the same
     * list plus the ids of every child habit of any selected group.
     * This ensures group moves carry their children along.
     */
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
        val tabs = tabManager.getAllTabs()
        val allIds = expandWithChildren(ids)

        if (tabs.isEmpty()) {
            // No tabs yet — offer to create one
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
            // Show existing tabs + "New tab…"
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

    /**
     * Moves [ids] to [tabId] using single-ownership semantics, then refreshes
     * the tab bar and the active filter.
     */
    private fun performMove(ids: List<Long>, tabId: String) {
        ids.forEach { tabManager.moveItemToTab(it, tabId) }
        refreshTabBarAndFilter(tabId)
    }

    /** Refreshes the tab bar chips and switches the active filter to [tabId]. */
    private fun refreshTabBarAndFilter(tabId: String) {
        rootView.tabBar.setTabs(tabManager.getAllTabs())
        rootView.tabBar.setSelectedTab(tabId)
        tabManager.saveActiveTab(tabId)
        component.listHabitsSelectionMenu.isOnCustomTab = true
        adapter.tabFilter = tabManager.getTab(tabId)?.habitIds?.toSet()
    }

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

    private fun parseIntents() {
        if (intent == null) return
        if (intent.action == ACTION_EDIT) {
            val habitId = intent.extras?.getLong("habit")
            val timestamp = intent.extras?.getLong("timestamp")
            if (habitId != null && timestamp != null) {
                val habit = appComponent.habitList.getById(habitId) ?: appComponent.habitGroupList.getHabitByID(habitId)!!
                component.listHabitsBehavior.onEdit(habit, Timestamp(timestamp))
            }
        }
        intent.getLongExtra("CLEAR_NOTIFICATION_HABIT_ID", -1).takeIf { it != -1L }?.let { id ->
            val dismissHabit = appComponent.habitList.getById(id) ?: appComponent.habitGroupList.getHabitByID(id)
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
    }
}
