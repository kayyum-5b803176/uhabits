package org.isoron.uhabits.activities.habits.list.tabs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Manages tab metadata (id, name, display order) in SharedPreferences.
 *
 * Tab *membership* lives in the DB via Habit.tabId / HabitGroup.tabId.
 * This class only tracks which tabs exist and which one is active.
 */
class TabManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized fun getAllTabs(): List<HabitTab> = deserialize()

    @Synchronized fun getTab(id: String): HabitTab? = getAllTabs().firstOrNull { it.id == id }

    @Synchronized fun getDefaultTabId(): String? = getAllTabs().firstOrNull()?.id

    @Synchronized
    fun addTab(name: String): HabitTab {
        val tabs = deserialize().toMutableList()
        val tab = HabitTab(id = UUID.randomUUID().toString(), name = name.trim())
        tabs.add(tab)
        serialize(tabs)
        return tab
    }

    @Synchronized
    fun renameTab(id: String, newName: String) {
        val tabs = deserialize().toMutableList()
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx >= 0) {
            tabs[idx] = tabs[idx].copy(name = newName.trim())
            serialize(tabs)
        }
    }

    /**
     * Deletes the tab. Caller must clear habit.tabId in DB for all members.
     */
    @Synchronized
    fun deleteTab(id: String) {
        val tabs = deserialize().toMutableList()
        tabs.removeAll { it.id == id }
        serialize(tabs)
        if (prefs.getString(KEY_ACTIVE_TAB, null) == id) {
            prefs.edit().remove(KEY_ACTIVE_TAB).apply()
        }
    }

    @Synchronized
    fun saveActiveTab(tabId: String?) {
        prefs.edit().apply {
            if (tabId == null) remove(KEY_ACTIVE_TAB) else putString(KEY_ACTIVE_TAB, tabId)
        }.apply()
    }

    @Synchronized
    fun loadActiveTab(): String? {
        val saved = prefs.getString(KEY_ACTIVE_TAB, null) ?: return null
        return if (getAllTabs().any { it.id == saved }) saved else null
    }

    private fun deserialize(): List<HabitTab> {
        val json = prefs.getString(KEY_TABS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                HabitTab(id = obj.getString(FIELD_ID), name = obj.getString(FIELD_NAME))
            }
        } catch (e: Exception) {
            prefs.edit().remove(KEY_TABS).apply()
            emptyList()
        }
    }

    private fun serialize(tabs: List<HabitTab>) {
        val array = JSONArray()
        tabs.forEach { tab ->
            val obj = JSONObject()
            obj.put(FIELD_ID, tab.id)
            obj.put(FIELD_NAME, tab.name)
            array.put(obj)
        }
        prefs.edit().putString(KEY_TABS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "habit_tabs"
        private const val KEY_TABS = "tabs"
        private const val KEY_ACTIVE_TAB = "active_tab"
        private const val FIELD_ID = "id"
        private const val FIELD_NAME = "name"
    }
}
