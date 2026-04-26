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
package org.isoron.uhabits.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.isoron.uhabits.HabitsApplication.Companion.isTestMode
import org.isoron.uhabits.HabitsDatabaseOpener
import org.isoron.uhabits.core.DATABASE_FILENAME
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.utils.DateFormats.Companion.getBackupDateFormat
import org.isoron.uhabits.core.utils.DateUtils.Companion.getLocalTime
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat

object DatabaseUtils {
    private var opener: HabitsDatabaseOpener? = null

    @JvmStatic
    fun getDatabaseFile(context: Context): File {
        val databaseFilename = databaseFilename
        val root = context.filesDir.path
        return File("$root/../databases/$databaseFilename")
    }

    private val databaseFilename: String
        get() {
            var databaseFilename: String = DATABASE_FILENAME
            if (isTestMode()) databaseFilename = "test.db"
            return databaseFilename
        }

    fun initializeDatabase(context: Context?) {
        opener = HabitsDatabaseOpener(
            context!!,
            databaseFilename,
            DATABASE_VERSION
        )
    }

    @JvmStatic
    @Throws(IOException::class)
    fun saveDatabaseCopy(context: Context, dir: File): String {
        val dateFormat: SimpleDateFormat = getBackupDateFormat()
        val date = dateFormat.format(getLocalTime())
        val filename = "${dir.absolutePath}/Loop Habits Backup $date.db"
        Log.i("DatabaseUtils", "Writing: $filename")
        val db = getDatabaseFile(context)
        val dbCopy = File(filename)
        db.copyTo(dbCopy)
        return dbCopy.absolutePath
    }

    fun openDatabase(): SQLiteDatabase {
        checkNotNull(opener)
        return opener!!.writableDatabase
    }

    /**
     * Closes the current DB connection, replaces the DB file with [source],
     * and deletes any WAL/SHM files so the new file is used cleanly.
     * Call [openDatabase] or restart the Activity to reopen.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun replaceDatabase(context: Context, source: File) {
        // Close the SQLiteOpenHelper so Android releases its file lock
        opener?.close()

        val dest = getDatabaseFile(context)
        source.copyTo(dest, overwrite = true)

        // Remove WAL/SHM sidecar files from the OLD connection so SQLite
        // does not try to replay old journal entries into the new file.
        File("${dest.path}-wal").delete()
        File("${dest.path}-shm").delete()

        // Re-init so the next openDatabase() call works correctly
        opener = HabitsDatabaseOpener(context, databaseFilename, DATABASE_VERSION)
    }
}
