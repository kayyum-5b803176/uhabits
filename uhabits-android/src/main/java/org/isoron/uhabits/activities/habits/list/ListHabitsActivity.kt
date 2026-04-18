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

class ListHabitsActivity : AppCompatActivity(), Preferences.Listener {

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
        setupAddToTabCallback()
        // ---------------------

        setContentView(rootView)
    }

    override fun onPause() {
        midnightTimer.onPause()
        screen.onDetached()
        adapter.cancelRefresh()
        dismissCurrentDialog()
        super.onPause()
    }

    override fun onResume() {
        adapter.refresh()
        screen.onAttached()
        rootView.postInvalidate()
        midnightTimer.onResume()

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
            adapter.tabFilter = tabManager.getTab(restoredTabId)?.habitIds?.toSet()
        }
        // (if null, bar already defaults to "All" with no filter – nothing to do)

        rootView.tabBar.listener = object : org.isoron.uhabits.activities.habits.list.tabs.TabBarView.Listener {

            override fun onTabSelected(tabId: String?) {
                tabManager.saveActiveTab(tabId)
                adapter.tabFilter = if (tabId == null) null
                else tabManager.getTab(tabId)?.habitIds?.toSet()
            }

            override fun onTabCreated(name: String) {
                val tab = tabManager.addTab(name)
                rootView.tabBar.setTabs(tabManager.getAllTabs())
                // Auto-select and persist the newly created tab
                rootView.tabBar.setSelectedTab(tab.id)
                tabManager.saveActiveTab(tab.id)
                adapter.tabFilter = tab.habitIds.toSet()
            }

            override fun onTabRenamed(tabId: String, newName: String) {
                tabManager.renameTab(tabId, newName)
                rootView.tabBar.setTabs(tabManager.getAllTabs())
            }

            override fun onTabDeleted(tabId: String) {
                tabManager.deleteTab(tabId)
                // Fall back to "All" and clear the persisted selection
                tabManager.saveActiveTab(null)
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
    private fun setupAddToTabCallback() {
        component.listHabitsSelectionMenu.addToTabCallback = { habitIds ->
            showAddToTabDialog(habitIds)
        }
    }

    private fun showAddToTabDialog(habitIds: List<Long>) {
        val tabs = tabManager.getAllTabs()

        if (tabs.isEmpty()) {
            // No tabs yet – prompt to create one first
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
                        habitIds.forEach { tabManager.addHabitToTab(it, tab.id) }
                        refreshTabBarAndFilter(tab.id)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            // Show existing tabs + a "New tab…" entry at the end
            val labels = tabs.map { it.name }.toMutableList()
            labels.add(getString(R.string.tab_create_title) + "…")

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.add_to_tab))
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which < tabs.size) {
                        // Existing tab selected
                        val tab = tabs[which]
                        habitIds.forEach { tabManager.addHabitToTab(it, tab.id) }
                        refreshTabBarAndFilter(tab.id)
                    } else {
                        // "New tab…" selected – ask for a name first
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
                                    habitIds.forEach { tabManager.addHabitToTab(it, tab.id) }
                                    refreshTabBarAndFilter(tab.id)
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
                .show()
        }
    }

    /** Refreshes the tab bar chips and switches the active filter to [tabId]. */
    private fun refreshTabBarAndFilter(tabId: String) {
        rootView.tabBar.setTabs(tabManager.getAllTabs())
        rootView.tabBar.setSelectedTab(tabId)
        tabManager.saveActiveTab(tabId)
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
