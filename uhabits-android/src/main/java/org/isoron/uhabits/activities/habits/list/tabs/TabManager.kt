package org.isoron.uhabits.activities.habits.list.tabs

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.isoron.uhabits.utils.DatabaseUtils
import java.security.MessageDigest

/**
 * Manages tab metadata (id, name, order, privacy) and the active-tab setting.
 * All tab data lives in the SQLite database:
 *   - Table `tabs`     : id TEXT PK, name TEXT, position INTEGER, is_private INTEGER
 *   - Table `settings` : key TEXT PK, value TEXT
 *
 * Private tab secrets (PIN hash, last-non-private tab) are stored in the
 * `settings` table under well-known keys defined in [SettingsKey].
 */
class TabManager(context: Context) {

    private val appContext = context.applicationContext
    private val db: SQLiteDatabase get() = DatabaseUtils.openDatabase()

    private object SettingsKey {
        const val ACTIVE_TAB       = "active_tab"
        const val PRIVATE_TAB_PIN  = "private_tab_pin"      // SHA-256 hex of 4-digit PIN
        const val LAST_NON_PRIVATE = "last_non_private_tab" // tabId or "" (= "All")
    }

    // -----------------------------------------------------------------------
    // Querying
    // -----------------------------------------------------------------------

    @Synchronized
    fun getAllTabs(): List<HabitTab> {
        return try {
            val result = mutableListOf<HabitTab>()
            db.rawQuery(
                "SELECT id, name, position, is_private FROM tabs ORDER BY position ASC", null
            ).use { c ->
                while (c.moveToNext()) {
                    result.add(HabitTab(
                        id        = c.getString(0),
                        name      = c.getString(1),
                        position  = c.getInt(2),
                        isPrivate = c.getInt(3) != 0
                    ))
                }
            }
            result
        } catch (e: Exception) { emptyList() }
    }

    @Synchronized fun getTab(id: String): HabitTab? = getAllTabs().firstOrNull { it.id == id }

    @Synchronized fun getDefaultTabId(): String? = getAllTabs().firstOrNull()?.id

    @Synchronized fun hasAnyPrivateTab(): Boolean = getAllTabs().any { it.isPrivate }

    // -----------------------------------------------------------------------
    // Tab mutations
    // -----------------------------------------------------------------------

    @Synchronized
    fun addTab(name: String, isPrivate: Boolean = false): HabitTab {
        val id       = java.util.UUID.randomUUID().toString()
        val position = (getAllTabs().maxByOrNull { it.position }?.position ?: -1) + 1
        db.execSQL(
            "INSERT INTO tabs (id, name, position, is_private) VALUES (?, ?, ?, ?)",
            arrayOf(id, name.trim(), position, if (isPrivate) 1 else 0)
        )
        return HabitTab(id = id, name = name.trim(), position = position, isPrivate = isPrivate)
    }

    @Synchronized
    fun renameTab(id: String, newName: String) {
        db.execSQL("UPDATE tabs SET name = ? WHERE id = ?", arrayOf(newName.trim(), id))
    }

    @Synchronized
    fun setTabPrivate(id: String, isPrivate: Boolean) {
        db.execSQL(
            "UPDATE tabs SET is_private = ? WHERE id = ?",
            arrayOf(if (isPrivate) 1 else 0, id)
        )
    }

    @Synchronized
    fun deleteTab(id: String) {
        db.execSQL("DELETE FROM tabs WHERE id = ?", arrayOf(id))
        val active = loadActiveTab()
        if (active == id) db.execSQL("DELETE FROM settings WHERE key = '${SettingsKey.ACTIVE_TAB}'")
        // If no private tabs remain, clear the PIN too
        if (!hasAnyPrivateTab()) clearPin()
    }

    // -----------------------------------------------------------------------
    // Active-tab persistence
    // -----------------------------------------------------------------------

    @Synchronized
    fun saveActiveTab(tabId: String?) {
        try {
            if (tabId == null) {
                db.execSQL("DELETE FROM settings WHERE key = '${SettingsKey.ACTIVE_TAB}'")
            } else {
                db.execSQL(
                    "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)",
                    arrayOf(SettingsKey.ACTIVE_TAB, tabId)
                )
            }
        } catch (e: Exception) { /* table may not exist on very old DB */ }
    }

    @Synchronized
    fun loadActiveTab(): String? {
        return try {
            db.rawQuery(
                "SELECT value FROM settings WHERE key = '${SettingsKey.ACTIVE_TAB}'", null
            ).use { c ->
                if (!c.moveToFirst()) return null
                val saved = c.getString(0) ?: return null
                if (getAllTabs().any { it.id == saved }) saved else null
            }
        } catch (e: Exception) { null }
    }

    // -----------------------------------------------------------------------
    // Last-non-private tab
    // -----------------------------------------------------------------------

    /** Persists the tab the user was on before entering a private tab. null = "All". */
    @Synchronized
    fun saveLastNonPrivateTab(tabId: String?) {
        try {
            db.execSQL(
                "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)",
                arrayOf(SettingsKey.LAST_NON_PRIVATE, tabId ?: "")
            )
        } catch (e: Exception) { /* ignore */ }
    }

    /** Returns the tab id stored before the private tab was opened (null = "All"). */
    @Synchronized
    fun loadLastNonPrivateTab(): String? {
        return try {
            db.rawQuery(
                "SELECT value FROM settings WHERE key = '${SettingsKey.LAST_NON_PRIVATE}'", null
            ).use { c ->
                if (!c.moveToFirst()) return null
                val raw = c.getString(0) ?: return null
                if (raw.isEmpty()) null                                    // sentinel = "All"
                else if (getAllTabs().any { it.id == raw }) raw else null  // tab still exists
            }
        } catch (e: Exception) { null }
    }

    // -----------------------------------------------------------------------
    // PIN management
    // -----------------------------------------------------------------------

    /** Stores the SHA-256 hash of [plainPin] in the settings table. */
    @Synchronized
    fun savePin(plainPin: String) {
        try {
            db.execSQL(
                "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)",
                arrayOf(SettingsKey.PRIVATE_TAB_PIN, sha256(plainPin))
            )
        } catch (e: Exception) { /* ignore */ }
    }

    /** Returns true if [plainPin] matches the stored hash. */
    @Synchronized
    fun verifyPin(plainPin: String): Boolean {
        val stored = loadPinHash() ?: return false
        return sha256(plainPin) == stored
    }

    /** Returns the stored PIN hash, or null if no PIN has been set. */
    @Synchronized
    fun loadPinHash(): String? {
        return try {
            db.rawQuery(
                "SELECT value FROM settings WHERE key = '${SettingsKey.PRIVATE_TAB_PIN}'", null
            ).use { c ->
                if (!c.moveToFirst()) null else c.getString(0)
            }
        } catch (e: Exception) { null }
    }

    /** Returns true if a PIN has already been configured. */
    @Synchronized
    fun hasPin(): Boolean = loadPinHash() != null

    /** Removes the stored PIN. Called automatically when the last private tab is deleted. */
    @Synchronized
    fun clearPin() {
        try {
            db.execSQL("DELETE FROM settings WHERE key = '${SettingsKey.PRIVATE_TAB_PIN}'")
        } catch (e: Exception) { /* ignore */ }
    }

    // -----------------------------------------------------------------------
    // Settings backup — type-prefixed for safe restore
    // -----------------------------------------------------------------------

    fun syncPrefsToDb(targetDb: SQLiteDatabase) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
        try {
            targetDb.execSQL("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)")
            prefs.all.forEach { (key, value) ->
                val dbValue = when (value) {
                    null       -> return@forEach
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

    fun syncPrefsFromDb(sourceDb: SQLiteDatabase) {
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
                    val key      = c.getString(0).removePrefix(PREF_PREFIX)
                    val raw      = c.getString(1) ?: continue
                    val colonIdx = raw.indexOf(':')
                    if (colonIdx < 0) continue
                    val type     = raw.substring(0, colonIdx)
                    val strVal   = raw.substring(colonIdx + 1)
                    try {
                        when (type) {
                            "bool"   -> editor.putBoolean(key, strVal == "true")
                            "int"    -> strVal.toIntOrNull()?.let { editor.putInt(key, it) }
                            "long"   -> strVal.toLongOrNull()?.let { editor.putLong(key, it) }
                            "float"  -> strVal.toFloatOrNull()?.let { editor.putFloat(key, it) }
                            "string" -> editor.putString(key, strVal)
                            "set"    -> editor.putStringSet(
                                key, if (strVal.isEmpty()) emptySet() else strVal.split("|").toSet()
                            )
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("TabManager", "Skipping pref key=$key: ${e.message}")
                    }
                }
            }
            editor.apply()
        } catch (e: Exception) {
            android.util.Log.e("TabManager", "syncPrefsFromDb failed", e)
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREF_PREFIX = "pref:"
    }
}
