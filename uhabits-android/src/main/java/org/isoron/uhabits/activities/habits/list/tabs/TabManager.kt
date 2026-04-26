package org.isoron.uhabits.activities.habits.list.tabs

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.isoron.uhabits.utils.DatabaseUtils

/**
 * Manages tab metadata (id, name, order) and the active-tab setting.
 * All data lives in the SQLite database:
 *   - Table `tabs`     : id TEXT PK, name TEXT, position INTEGER
 *   - Table `settings` : key TEXT PK, value TEXT
 */
class TabManager(context: Context) {

    private val appContext = context.applicationContext
    private val db: SQLiteDatabase get() = DatabaseUtils.openDatabase()

    // -----------------------------------------------------------------------
    // Querying
    // -----------------------------------------------------------------------

    @Synchronized
    fun getAllTabs(): List<HabitTab> {
        return try {
            val result = mutableListOf<HabitTab>()
            db.rawQuery("SELECT id, name, position FROM tabs ORDER BY position ASC", null).use { c ->
                while (c.moveToNext()) {
                    result.add(HabitTab(id = c.getString(0), name = c.getString(1), position = c.getInt(2)))
                }
            }
            result
        } catch (e: Exception) { emptyList() }
    }

    @Synchronized fun getTab(id: String): HabitTab? = getAllTabs().firstOrNull { it.id == id }

    @Synchronized fun getDefaultTabId(): String? = getAllTabs().firstOrNull()?.id

    // -----------------------------------------------------------------------
    // Tab mutations
    // -----------------------------------------------------------------------

    @Synchronized
    fun addTab(name: String): HabitTab {
        val id = java.util.UUID.randomUUID().toString()
        val position = (getAllTabs().maxByOrNull { it.position }?.position ?: -1) + 1
        db.execSQL("INSERT INTO tabs (id, name, position) VALUES (?, ?, ?)", arrayOf(id, name.trim(), position))
        return HabitTab(id = id, name = name.trim(), position = position)
    }

    @Synchronized
    fun renameTab(id: String, newName: String) {
        db.execSQL("UPDATE tabs SET name = ? WHERE id = ?", arrayOf(newName.trim(), id))
    }

    @Synchronized
    fun deleteTab(id: String) {
        db.execSQL("DELETE FROM tabs WHERE id = ?", arrayOf(id))
        val active = loadActiveTab()
        if (active == id) db.execSQL("DELETE FROM settings WHERE key = 'active_tab'")
    }

    // -----------------------------------------------------------------------
    // Active-tab persistence
    // -----------------------------------------------------------------------

    @Synchronized
    fun saveActiveTab(tabId: String?) {
        try {
            if (tabId == null) {
                db.execSQL("DELETE FROM settings WHERE key = 'active_tab'")
            } else {
                db.execSQL(
                    "INSERT OR REPLACE INTO settings (key, value) VALUES ('active_tab', ?)",
                    arrayOf(tabId)
                )
            }
        } catch (e: Exception) { /* table may not exist on very old DB */ }
    }

    @Synchronized
    fun loadActiveTab(): String? {
        return try {
            db.rawQuery("SELECT value FROM settings WHERE key = 'active_tab'", null).use { c ->
                if (!c.moveToFirst()) return null
                val saved = c.getString(0) ?: return null
                if (getAllTabs().any { it.id == saved }) saved else null
            }
        } catch (e: Exception) { null }
    }

    // -----------------------------------------------------------------------
    // Settings backup — type-prefixed for safe restore
    // -----------------------------------------------------------------------

    /**
     * Writes all SharedPreferences into [targetDb] with type-prefixed values
     * (e.g. `bool:true`, `int:5`). Uses a copy DB so the live DB is never
     * touched during export.
     */
    fun syncPrefsToDb(targetDb: SQLiteDatabase) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
        try {
            targetDb.execSQL(
                "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)"
            )
            prefs.all.forEach { (key, value) ->
                val dbValue = when (value) {
                    null      -> return@forEach
                    is Boolean -> "bool:$value"
                    is Int     -> "int:$value"
                    is Long    -> "long:$value"
                    is Float   -> "float:$value"
                    is String  -> "string:$value"
                    is Set<*>  -> "set:${value.joinToString("|")}"
                    else       -> return@forEach
                }
                targetDb.execSQL(
                    "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)",
                    arrayOf("$PREF_PREFIX$key", dbValue)
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("TabManager", "syncPrefsToDb failed", e)
        }
    }

    /**
     * Reads all `pref:*` rows from [sourceDb] and writes them to SharedPreferences.
     * Gracefully skips if the `settings` table does not exist (old backup).
     * Type is encoded in the value prefix — no assumptions about existing keys.
     */
    fun syncPrefsFromDb(sourceDb: SQLiteDatabase) {
        // Check table exists first — old backups won't have it
        val tableExists = sourceDb.rawQuery(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='settings'", null
        ).use { c -> c.moveToFirst() && c.getInt(0) > 0 }
        if (!tableExists) return

        val editor = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(appContext).edit()

        try {
            sourceDb.rawQuery(
                "SELECT key, value FROM settings WHERE key LIKE ?",
                arrayOf("$PREF_PREFIX%")
            ).use { c ->
                while (c.moveToNext()) {
                    val key = c.getString(0).removePrefix(PREF_PREFIX)
                    val raw = c.getString(1) ?: continue
                    val colonIdx = raw.indexOf(':')
                    if (colonIdx < 0) continue
                    val type   = raw.substring(0, colonIdx)
                    val strVal = raw.substring(colonIdx + 1)
                    try {
                        when (type) {
                            "bool"   -> editor.putBoolean(key, strVal == "true")
                            "int"    -> strVal.toIntOrNull()?.let { editor.putInt(key, it) }
                            "long"   -> strVal.toLongOrNull()?.let { editor.putLong(key, it) }
                            "float"  -> strVal.toFloatOrNull()?.let { editor.putFloat(key, it) }
                            "string" -> editor.putString(key, strVal)
                            "set"    -> editor.putStringSet(
                                key, if (strVal.isEmpty()) emptySet()
                                     else strVal.split("|").toSet()
                            )
                        }
                    } catch (e: Exception) {
                        // Skip individual corrupt keys — never crash on restore
                        android.util.Log.w("TabManager", "Skipping pref key=$key: ${e.message}")
                    }
                }
            }
            editor.apply()
        } catch (e: Exception) {
            android.util.Log.e("TabManager", "syncPrefsFromDb failed", e)
        }
    }

    companion object {
        private const val PREF_PREFIX = "pref:"
    }
}
