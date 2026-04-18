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

package org.isoron.uhabits.activities.habits.list.tabs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Manages the lifecycle of [HabitTab] objects.
 *
 * Tabs are serialised as a JSON array and stored in a dedicated
 * SharedPreferences file so they survive app restarts without touching the
 * main habits database.
 *
 * Thread-safety: all public methods are @Synchronized so they are safe to call
 * from a background thread, although in practice they are called from the main
 * thread only.
 */
class TabManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -----------------------------------------------------------------------
    // Querying
    // -----------------------------------------------------------------------

    /** Returns an ordered, immutable snapshot of all tabs. */
    @Synchronized
    fun getAllTabs(): List<HabitTab> = deserialize()

    /** Returns the tab with the given [id], or null if it does not exist. */
    @Synchronized
    fun getTab(id: String): HabitTab? = getAllTabs().firstOrNull { it.id == id }

    // -----------------------------------------------------------------------
    // Mutations
    // -----------------------------------------------------------------------

    /**
     * Creates a new tab with the given [name] and returns it.
     * The new tab is appended at the end of the tab list.
     */
    @Synchronized
    fun addTab(name: String): HabitTab {
        val tabs = deserialize().toMutableList()
        val tab = HabitTab(id = UUID.randomUUID().toString(), name = name.trim())
        tabs.add(tab)
        serialize(tabs)
        return tab
    }

    /**
     * Renames the tab identified by [id] to [newName].
     * Does nothing if the tab does not exist.
     */
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
     * Permanently deletes the tab identified by [id].
     * Does nothing if the tab does not exist.
     */
    @Synchronized
    fun deleteTab(id: String) {
        val tabs = deserialize().toMutableList()
        tabs.removeAll { it.id == id }
        serialize(tabs)
    }

    /** Adds [habitId] to the tab identified by [tabId]. No-op if already present. */
    @Synchronized
    fun addHabitToTab(habitId: Long, tabId: String) {
        val tabs = deserialize().toMutableList()
        val idx = tabs.indexOfFirst { it.id == tabId }
        if (idx >= 0) {
            val tab = tabs[idx]
            if (!tab.habitIds.contains(habitId)) {
                tab.habitIds.add(habitId)
                serialize(tabs)
            }
        }
    }

    /** Removes [habitId] from the tab identified by [tabId]. No-op if not present. */
    @Synchronized
    fun removeHabitFromTab(habitId: Long, tabId: String) {
        val tabs = deserialize().toMutableList()
        val idx = tabs.indexOfFirst { it.id == tabId }
        if (idx >= 0 && tabs[idx].habitIds.remove(habitId)) {
            serialize(tabs)
        }
    }

    /** Removes [habitId] from **all** tabs. Useful when a habit is deleted. */
    @Synchronized
    fun removeHabitFromAllTabs(habitId: Long) {
        val tabs = deserialize().toMutableList()
        var changed = false
        tabs.forEach { changed = changed or it.habitIds.remove(habitId) }
        if (changed) serialize(tabs)
    }

    // -----------------------------------------------------------------------
    // Serialisation helpers
    // -----------------------------------------------------------------------

    private fun deserialize(): List<HabitTab> {
        val json = prefs.getString(KEY_TABS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val idsArray = obj.getJSONArray(FIELD_HABIT_IDS)
                val ids = (0 until idsArray.length()).map { j -> idsArray.getLong(j) }.toMutableSet()
                HabitTab(
                    id = obj.getString(FIELD_ID),
                    name = obj.getString(FIELD_NAME),
                    habitIds = ids
                )
            }
        } catch (e: Exception) {
            // If JSON is corrupt, reset gracefully
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
            val idsArray = JSONArray()
            tab.habitIds.forEach { idsArray.put(it) }
            obj.put(FIELD_HABIT_IDS, idsArray)
            array.put(obj)
        }
        prefs.edit().putString(KEY_TABS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "habit_tabs"
        private const val KEY_TABS = "tabs"
        private const val FIELD_ID = "id"
        private const val FIELD_NAME = "name"
        private const val FIELD_HABIT_IDS = "habitIds"
    }
}
