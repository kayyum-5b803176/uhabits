package org.isoron.uhabits.activities.habits.list.tabs

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.isoron.uhabits.utils.DatabaseUtils

/**
 * Manages tab metadata (id, name, order) and the active-tab setting.
 *
 * All data lives in the SQLite database:
 *   - Table `tabs`    : id TEXT PK, name TEXT, position INTEGER
 *   - Table `settings`: key TEXT PK, value TEXT   (key = "active_tab")
 *
 * Because everything is in the DB, a plain DB backup/restore automatically
 * includes all tab data — no JSON sidecar or ZIP archive needed.
 */
class TabManager(context: Context) {

    // DatabaseUtils.openDatabase() is safe after HabitsApplication.onCreate().
    private val db: SQLiteDatabase get() = DatabaseUtils.openDatabase()

    // -----------------------------------------------------------------------
    // Querying
    // -----------------------------------------------------------------------

    @Synchronized
    fun getAllTabs(): List<HabitTab> {
        val result = mutableListOf<HabitTab>()
        db.rawQuery(
            "SELECT id, name FROM tabs ORDER BY position ASC", null
        ).use { c ->
            while (c.moveToNext()) {
                result.add(HabitTab(id = c.getString(0), name = c.getString(1)))
            }
        }
        return result
    }

    @Synchronized
    fun getTab(id: String): HabitTab? = getAllTabs().firstOrNull { it.id == id }

    @Synchronized
    fun getDefaultTabId(): String? = getAllTabs().firstOrNull()?.id

    // -----------------------------------------------------------------------
    // Tab mutations
    // -----------------------------------------------------------------------

    @Synchronized
    fun addTab(name: String): HabitTab {
        val id = java.util.UUID.randomUUID().toString()
        val position = (getAllTabs().maxByOrNull { it.position }?.position ?: -1) + 1
        db.execSQL(
            "INSERT INTO tabs (id, name, position) VALUES (?, ?, ?)",
            arrayOf(id, name.trim(), position)
        )
        return HabitTab(id = id, name = name.trim(), position = position)
    }

    @Synchronized
    fun renameTab(id: String, newName: String) {
        db.execSQL("UPDATE tabs SET name = ? WHERE id = ?", arrayOf(newName.trim(), id))
    }

    /**
     * Deletes the tab. The caller is responsible for clearing `tab_id` on
     * all habits/groups that belonged to this tab so they become unassigned.
     */
    @Synchronized
    fun deleteTab(id: String) {
        db.execSQL("DELETE FROM tabs WHERE id = ?", arrayOf(id))
        // If this was the active tab, clear the setting
        db.rawQuery("SELECT value FROM settings WHERE key = 'active_tab'", null).use { c ->
            if (c.moveToFirst() && c.getString(0) == id) {
                db.execSQL("DELETE FROM settings WHERE key = 'active_tab'")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Active-tab persistence
    // -----------------------------------------------------------------------

    @Synchronized
    fun saveActiveTab(tabId: String?) {
        if (tabId == null) {
            db.execSQL("DELETE FROM settings WHERE key = 'active_tab'")
        } else {
            db.execSQL(
                "INSERT OR REPLACE INTO settings (key, value) VALUES ('active_tab', ?)",
                arrayOf(tabId)
            )
        }
    }

    @Synchronized
    fun loadActiveTab(): String? {
        db.rawQuery(
            "SELECT value FROM settings WHERE key = 'active_tab'", null
        ).use { c ->
            if (!c.moveToFirst()) return null
            val saved = c.getString(0) ?: return null
            // Guard: tab must still exist
            return if (getAllTabs().any { it.id == saved }) saved else null
        }
    }

    companion object {
        const val BACKUP_ENTRY_DB = "uhabits.db"

        // Prefix used to distinguish preference rows from other settings rows
        private const val PREF_PREFIX = "pref:"
    }

    // -----------------------------------------------------------------------
    // Settings backup — syncs app SharedPreferences ↔ DB settings table
    // -----------------------------------------------------------------------

    /**
     * Writes every key-value pair from [PreferenceManager.getDefaultSharedPreferences]
     * into the `settings` table before export. Called right before the DB file
     * is copied so the backup is self-contained.
     */
    fun syncPrefsToDb(context: Context) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val editor = db  // db is already open
        prefs.all.forEach { (key, value) ->
            val dbKey = "$PREF_PREFIX$key"
            val dbValue = when (value) {
                null -> return@forEach
                is Boolean -> if (value) "true" else "false"
                is Int -> value.toString()
                is Long -> value.toString()
                is Float -> value.toString()
                is String -> value
                is Set<*> -> value.joinToString("|")
                else -> return@forEach
            }
            db.execSQL(
                "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)",
                arrayOf(dbKey, dbValue)
            )
        }
    }

    /**
     * Reads all preference rows from the `settings` table and applies them to
     * [PreferenceManager.getDefaultSharedPreferences]. Called after DB replace
     * so all app settings match the backup.
     *
     * Only rows whose key starts with [PREF_PREFIX] are touched; other rows
     * (e.g. `active_tab`) are left alone.
     */
    fun syncPrefsFromDb(context: Context) {
        val sharedPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val editor = sharedPrefs.edit()

        db.rawQuery(
            "SELECT key, value FROM settings WHERE key LIKE '$PREF_PREFIX%'", null
        ).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0).removePrefix(PREF_PREFIX)
                val raw = c.getString(1) ?: continue

                // Try to determine the existing type to restore with the right type.
                // If the key doesn't exist yet, fall back to String.
                val existing = sharedPrefs.all[key]
                when (existing) {
                    is Boolean -> editor.putBoolean(key, raw == "true")
                    is Int -> raw.toIntOrNull()?.let { editor.putInt(key, it) }
                    is Long -> raw.toLongOrNull()?.let { editor.putLong(key, it) }
                    is Float -> raw.toFloatOrNull()?.let { editor.putFloat(key, it) }
                    is Set<*> -> editor.putStringSet(key, raw.split("|").toSet())
                    else -> editor.putString(key, raw) // new keys default to String
                }
            }
        }
        editor.apply()
    }
}
