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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dagger.Lazy
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.CheckmarkDialog
import org.isoron.uhabits.activities.common.dialogs.ColorPickerDialogFactory
import org.isoron.uhabits.activities.common.dialogs.ConfirmDeleteDialog
import org.isoron.uhabits.activities.common.dialogs.NumberDialog
import org.isoron.uhabits.activities.habits.edit.HabitTypeDialog
import org.isoron.uhabits.activities.habits.list.views.HabitCardListAdapter
import org.isoron.uhabits.core.commands.ArchiveHabitsCommand
import org.isoron.uhabits.core.commands.BlockSkippedDayCommand
import org.isoron.uhabits.core.commands.ChangeHabitColorCommand
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.DeleteHabitGroupsCommand
import org.isoron.uhabits.core.commands.DeleteHabitsCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.commands.UnarchiveHabitsCommand
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGroup
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.TaskRunner
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.ui.callbacks.OnColorPickedCallback
import org.isoron.uhabits.core.ui.callbacks.OnConfirmedCallback
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior.Message.COULD_NOT_EXPORT
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior.Message.COULD_NOT_GENERATE_BUG_REPORT
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior.Message.DATABASE_REPAIRED
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior.Message.FILE_NOT_RECOGNIZED
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior.Message.IMPORT_FAILED
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior.Message.IMPORT_SUCCESSFUL
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsMenuBehavior
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsSelectionMenuBehavior
import org.isoron.uhabits.inject.ActivityContext
import org.isoron.uhabits.inject.ActivityScope
import org.isoron.uhabits.intents.IntentFactory
import org.isoron.uhabits.tasks.ExportDBTaskFactory
import org.isoron.uhabits.tasks.ImportDataTask
import org.isoron.uhabits.tasks.ImportDataTaskFactory
import org.isoron.uhabits.utils.copyTo
import org.isoron.uhabits.utils.currentTheme
import org.isoron.uhabits.utils.dismissCurrentAndShow
import org.isoron.uhabits.utils.restartWithFade
import org.isoron.uhabits.utils.showMessage
import org.isoron.uhabits.utils.showSendEmailScreen
import org.isoron.uhabits.utils.showSendFileScreen
import org.isoron.uhabits.utils.DatabaseUtils
import java.io.File
import java.io.IOException
import javax.inject.Inject

const val RESULT_IMPORT_DATA = 101
const val RESULT_EXPORT_CSV = 102
const val RESULT_EXPORT_DB = 103
const val RESULT_BUG_REPORT = 104
const val RESULT_REPAIR_DB = 105
const val REQUEST_OPEN_DOCUMENT = 106
const val REQUEST_CREATE_DOCUMENT = 108
const val REQUEST_SETTINGS = 107

@ActivityScope
class ListHabitsScreen
@Inject constructor(
    @ActivityContext val context: Context,
    private val commandRunner: CommandRunner,
    private val intentFactory: IntentFactory,
    private val themeSwitcher: ThemeSwitcher,
    private val adapter: HabitCardListAdapter,
    private val taskRunner: TaskRunner,
    private val exportDBFactory: ExportDBTaskFactory,
    private val importTaskFactory: ImportDataTaskFactory,
    private val colorPickerFactory: ColorPickerDialogFactory,
    private val behavior: Lazy<ListHabitsBehavior>,
    private val preferences: Preferences,
    private val rootView: Lazy<ListHabitsRootView>
) : CommandRunner.Listener,
    ListHabitsBehavior.Screen,
    ListHabitsMenuBehavior.Screen,
    ListHabitsSelectionMenuBehavior.Screen {

    val activity = (context as AppCompatActivity)

    /** Set by [ListHabitsActivity] — used to sync prefs ↔ DB on export/import. */
    lateinit var tabManager: org.isoron.uhabits.activities.habits.list.tabs.TabManager

    fun onAttached() {
        commandRunner.addListener(this)
    }

    fun onDetached() {
        commandRunner.removeListener(this)
    }

    override fun onCommandFinished(command: Command) {
        val msg = getExecuteString(command)
        if (msg != null) activity.showMessage(msg)
    }

    fun onResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_OPEN_DOCUMENT -> onOpenDocumentResult(resultCode, data)
            REQUEST_CREATE_DOCUMENT -> onCreateDocumentResult(resultCode, data)
            REQUEST_SETTINGS -> onSettingsResult(resultCode)
        }
    }

    private fun onSettingsResult(resultCode: Int) {
        when (resultCode) {
            RESULT_IMPORT_DATA -> showImportScreen()
            RESULT_EXPORT_CSV -> behavior.get().onExportCSV()
            RESULT_EXPORT_DB -> onExportDB()
            RESULT_BUG_REPORT -> behavior.get().onSendBugReport()
            RESULT_REPAIR_DB -> behavior.get().onRepairDB()
        }
    }

    override fun applyTheme() {
        themeSwitcher.apply()
        activity.restartWithFade(ListHabitsActivity::class.java)
    }

    override fun showAboutScreen() {
        val intent = intentFactory.startAboutActivity(activity)
        activity.startActivity(intent)
    }

    override fun showSelectHabitTypeDialog(groupId: Long?) {
        val dialog = HabitTypeDialog(groupId)
        dialog.show(activity.supportFragmentManager, "habitType")
    }

    override fun showDeleteConfirmationScreen(callback: OnConfirmedCallback, quantity: Int) {
        ConfirmDeleteDialog(activity, callback, quantity).dismissCurrentAndShow()
    }

    override fun showEditHabitsScreen(selected: List<Habit>) {
        val intent = intentFactory.startEditActivity(activity, selected[0])
        activity.startActivity(intent)
    }

    override fun showEditHabitGroupScreen(selected: List<HabitGroup>) {
        val intent = intentFactory.startEditGroupActivity(activity, selected[0])
        activity.startActivity(intent)
    }

    override fun showHabitGroupPickerDialog(selected: List<Habit>) {
        val intent = intentFactory.startHabitGroupPickerActivity(activity, selected)
        activity.startActivity(intent)
    }

    override fun showFAQScreen() {
        val intent = intentFactory.viewFAQ(activity)
        activity.startActivity(intent)
    }

    override fun showHabitScreen(h: Habit) {
        val intent = intentFactory.startShowHabitActivity(activity, h)
        activity.startActivity(intent)
    }

    override fun showHabitGroupScreen(hgr: HabitGroup) {
        val intent = intentFactory.startShowHabitGroupActivity(activity, hgr)
        activity.startActivity(intent)
    }

    fun showImportScreen() {
        val intent = intentFactory.openDocument()
        activity.startActivityForResult(intent, REQUEST_OPEN_DOCUMENT)
    }

    override fun showIntroScreen() {
        val intent = intentFactory.startIntroActivity(activity)
        activity.startActivity(intent)
    }

    override fun showMessage(m: ListHabitsBehavior.Message) {
        activity.showMessage(
            activity.resources.getString(
                when (m) {
                    COULD_NOT_EXPORT -> R.string.could_not_export
                    IMPORT_SUCCESSFUL -> R.string.habits_imported
                    IMPORT_FAILED -> R.string.could_not_import
                    DATABASE_REPAIRED -> R.string.database_repaired
                    COULD_NOT_GENERATE_BUG_REPORT -> R.string.bug_report_failed
                    FILE_NOT_RECOGNIZED -> R.string.file_not_recognized
                }
            )
        )
    }

    override fun showSendBugReportToDeveloperScreen(log: String) {
        val to = R.string.bugReportTo
        val subject = R.string.bugReportSubject
        activity.showSendEmailScreen(to, subject, log)
    }

    override fun showSendFileScreen(filename: String) {
        activity.showSendFileScreen(filename)
    }

    override fun showConfetti(color: PaletteColor, x: Float, y: Float) {
        // confetti removed
    }

    override fun showSettingsScreen() {
        val intent = intentFactory.startSettingsActivity(activity)
        activity.startActivityForResult(intent, REQUEST_SETTINGS)
    }

    override fun showColorPicker(defaultColor: PaletteColor, callback: OnColorPickedCallback) {
        val picker = colorPickerFactory.create(defaultColor, themeSwitcher.currentTheme!!)
        picker.setListener(callback)
        picker.dismissCurrentAndShow(activity.supportFragmentManager, "picker")
    }

    override fun showNumberPopup(
        value: Double,
        notes: String,
        callback: ListHabitsBehavior.NumberPickerCallback
    ) {
        val fm = (context as AppCompatActivity).supportFragmentManager
        val dialog = NumberDialog()
        dialog.arguments = Bundle().apply {
            putDouble("value", value)
            putString("notes", notes)
        }
        dialog.onToggle = { v, n, x, y -> callback.onNumberPicked(v, n, x, y) }
        dialog.dismissCurrentAndShow(fm, "numberDialog")
    }

    override fun showCheckmarkPopup(
        selectedValue: Int,
        notes: String,
        color: PaletteColor,
        callback: ListHabitsBehavior.CheckMarkDialogCallback
    ) {
        val theme = rootView.get().currentTheme()
        val fm = (context as AppCompatActivity).supportFragmentManager
        val dialog = CheckmarkDialog()
        dialog.arguments = Bundle().apply {
            putInt("color", theme.color(color).toInt())
            putInt("value", selectedValue)
            putString("notes", notes)
        }
        dialog.onToggle = { v, n, x, y -> callback.onNotesSaved(v, n, x, y) }
        dialog.dismissCurrentAndShow(fm, "checkmarkDialog")
    }

    private fun getExecuteString(command: Command): String? {
        when (command) {
            is ArchiveHabitsCommand -> {
                return activity.resources.getQuantityString(
                    R.plurals.toast_habits_archived,
                    command.selected.size
                )
            }
            is ChangeHabitColorCommand -> {
                return activity.resources.getQuantityString(
                    R.plurals.toast_habits_changed,
                    command.selected.size
                )
            }
            is CreateHabitCommand -> {
                return activity.resources.getString(R.string.toast_habit_created)
            }
            is DeleteHabitsCommand -> {
                return activity.resources.getQuantityString(
                    R.plurals.toast_habits_deleted,
                    command.selected.size
                )
            }
            is DeleteHabitGroupsCommand -> {
                return activity.resources.getQuantityString(
                    R.plurals.toast_habits_deleted,
                    command.selected.size
                )
            }
            is EditHabitCommand -> {
                return activity.resources.getQuantityString(R.plurals.toast_habits_changed, 1)
            }
            is UnarchiveHabitsCommand -> {
                return activity.resources.getQuantityString(
                    R.plurals.toast_habits_unarchived,
                    command.selected.size
                )
            }
            is BlockSkippedDayCommand -> {
                return activity.resources.getString(R.string.toast_day_auto_skip)
            }
            else -> return null
        }
    }

    /**
     * Optional callback invoked on the main thread after a successful import.
     * [ListHabitsActivity] uses this to reload the tab bar from the restored DB.
     */
    var onImportSuccess: (() -> Unit)? = null

    private fun onImportData(file: File, onFinished: () -> Unit) {
        taskRunner.execute(
            importTaskFactory.create(file) { result ->
                when (result) {
                    ImportDataTask.SUCCESS -> {
                        adapter.refresh()
                        activity.showMessage(activity.resources.getString(R.string.habits_imported))
                        onImportSuccess?.invoke()
                    }
                    ImportDataTask.NOT_RECOGNIZED -> {
                        activity.showMessage(activity.resources.getString(R.string.file_not_recognized))
                    }
                    else -> {
                        activity.showMessage(activity.resources.getString(R.string.could_not_import))
                    }
                }
                onFinished()
            }
        )
    }

    private fun onExportDB() {
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
            .format(java.util.Date())
        val fileName = "Loop Habits Backup $dateStr.db"
        activity.startActivityForResult(intentFactory.createDocument(fileName), REQUEST_CREATE_DOCUMENT)
    }

    /**
     * Export — writes settings to a *temp copy* of the DB so the live DB
     * is never modified.  Steps:
     * 1. Copy live DB → temp file
     * 2. Open temp file and write all SharedPreferences with type-prefixed values
     * 3. Stream temp file to SAF URI chosen by the user
     * 4. Delete temp file
     *
     * If anything goes wrong the live DB is completely untouched.
     */
    private fun onCreateDocumentResult(resultCode: Int, data: Intent?) {
        if (data?.data == null || resultCode != Activity.RESULT_OK) return
        val destUri = data.data!!
        var tempExport: File? = null
        try {
            val cacheDir = activity.externalCacheDir
            val liveDb  = DatabaseUtils.getDatabaseFile(activity)

            // 1. Make a temp copy of the live DB
            tempExport = File.createTempFile("export", ".db", cacheDir)
            liveDb.copyTo(tempExport!!, overwrite = true)

            // 2. Open the COPY and write settings into it (never touches live DB)
            android.database.sqlite.SQLiteDatabase.openDatabase(
                tempExport!!.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            ).use { copyDb ->
                tabManager.syncPrefsToDb(copyDb)
            }

            // 3. Stream the enriched copy to the SAF URI
            activity.contentResolver.openOutputStream(destUri)!!.buffered().use { out ->
                tempExport!!.inputStream().use { it.copyTo(out) }
            }

            activity.showMessage(activity.resources.getString(R.string.database_exported))
        } catch (e: Exception) {
            activity.showMessage(activity.resources.getString(R.string.could_not_export))
            android.util.Log.e("ListHabitsScreen", "Export failed", e)
        } finally {
            tempExport?.delete()
        }
    }

    /**
     * Import — direct file replace with full rollback on any failure.  Steps:
     * 1. Copy SAF stream → temp import file
     * 2. Validate: must be SQLite, version ≤ current
     * 3. Back up current DB → rollback file
     * 4. Replace live DB file with import file
     * 5. Open new DB and restore SharedPreferences (graceful if settings table absent)
     * 6. Restart activity
     *
     * On ANY failure after step 4: restore rollback file and report error.
     */
    private fun onOpenDocumentResult(resultCode: Int, data: Intent?) {
        if (data == null || resultCode != Activity.RESULT_OK) return

        var tempImport: File? = null
        var rollback:   File? = null

        try {
            val cacheDir = activity.externalCacheDir!!

            // 1. Copy SAF stream to temp file
            tempImport = File.createTempFile("import", ".db", cacheDir)
            activity.contentResolver.openInputStream(data.data!!)!!.use { ins ->
                ins.copyTo(tempImport!!.outputStream())
            }

            // 2. Validate
            if (!isSQLiteFile(tempImport!!)) {
                activity.showMessage(activity.resources.getString(R.string.file_not_recognized))
                return
            }
            val importVersion = android.database.sqlite.SQLiteDatabase.openDatabase(
                tempImport!!.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { it.version }
            if (importVersion > org.isoron.uhabits.core.DATABASE_VERSION) {
                activity.showMessage(activity.resources.getString(R.string.could_not_import))
                return
            }

            // 3. Back up current DB (rollback plan)
            val liveDb = DatabaseUtils.getDatabaseFile(activity)
            rollback = File.createTempFile("rollback", ".db", cacheDir)
            liveDb.copyTo(rollback!!, overwrite = true)

            // 4. Replace live DB
            DatabaseUtils.replaceDatabase(activity, tempImport!!)

            // 5. Restore SharedPreferences from new DB — graceful on any error
            try {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    DatabaseUtils.getDatabaseFile(activity).absolutePath,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                ).use { newDb ->
                    tabManager.syncPrefsFromDb(newDb)
                }
            } catch (e: Exception) {
                // Prefs restore failed — not fatal, app is still usable
                android.util.Log.w("ListHabitsScreen", "Pref restore skipped: ${e.message}")
            }

            // 6. Restart
            activity.showMessage(activity.resources.getString(R.string.habits_imported))
            activity.restartWithFade(ListHabitsActivity::class.java)

        } catch (e: Exception) {
            android.util.Log.e("ListHabitsScreen", "Import failed", e)
            // Rollback: restore original DB if we already replaced it
            rollback?.let { rb ->
                try {
                    DatabaseUtils.replaceDatabase(activity, rb)
                } catch (re: Exception) {
                    android.util.Log.e("ListHabitsScreen", "Rollback also failed", re)
                }
            }
            activity.showMessage(activity.resources.getString(R.string.could_not_import))
        } finally {
            tempImport?.delete()
            rollback?.delete()
        }
    }

    /** Returns true if [file] starts with the SQLite3 magic header bytes. */
    private fun isSQLiteFile(file: File): Boolean {
        if (file.length() < 16) return false
        return try {
            file.inputStream().use { s ->
                val magic = "SQLite format 3 "
                magic.all { s.read() == it.code }
            }
        } catch (e: Exception) { false }
    }
}
