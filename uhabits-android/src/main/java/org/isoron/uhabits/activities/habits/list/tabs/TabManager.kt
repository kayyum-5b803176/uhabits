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
 * ### Ownership model
 * Every habit/group either belongs to **exactly one** tab or is **unassigned**
 * (unassigned items are visible only on the "All" tab).  This is enforced by
 * [moveItemToTab], which always removes the item from its previous tab before
 * adding it to the new one.
 *
 * When a tab is deleted its items become unassigned — they are still visible
 * on "All" and can be moved to another tab later.
 *
 * ### Persistence
 * Tabs are serialised as JSON and stored in a dedicated SharedPreferences file
 * so they survive app restarts without touching the habits database.
 *
 * All public methods are @Synchronized.
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

    /**
     * Returns the id of the tab that owns [itemId], or null if the item is
     * unassigned (i.e. it only appears on "All").
     */
    @Synchronized
    fun getItemTabId(itemId: Long): String? =
        getAllTabs().firstOrNull { it.habitIds.contains(itemId) }?.id

    /**
     * Returns the id of the "default" tab — the first tab in the list — or
     * null if no tabs have been created yet.
     * Used when a new item is created while "All" is active.
     */
    @Synchronized
    fun getDefaultTabId(): String? = getAllTabs().firstOrNull()?.id

    // -----------------------------------------------------------------------
    // Tab mutations
    // -----------------------------------------------------------------------

    /**
     * Creates a new tab with the given [name] and returns it.
     * The new tab is appended at the end of the list.
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
     * Renames the tab identified by [id].
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
     * Items that belonged to this tab become **unassigned** — they are no
     * longer in any tab but remain visible on "All".
     * Does nothing if the tab does not exist.
     */
    @Synchronized
    fun deleteTab(id: String) {
        val tabs = deserialize().toMutableList()
        tabs.removeAll { it.id == id }
        serialize(tabs)
    }

    // -----------------------------------------------------------------------
    // Item ownership mutations
    // -----------------------------------------------------------------------

    /**
     * Moves [itemId] to the tab identified by [tabId].
     *
     * The item is **first removed from its current tab** (if any), then added
     * to [tabId]. This enforces the single-ownership invariant: an item can
     * live in at most one tab at a time.
     */
    @Synchronized
    fun moveItemToTab(itemId: Long, tabId: String) {
        val tabs = deserialize().toMutableList()
        // Remove from current owner (scan all tabs)
        tabs.forEach { it.habitIds.remove(itemId) }
        // Add to the target tab
        val idx = tabs.indexOfFirst { it.id == tabId }
        if (idx >= 0) tabs[idx].habitIds.add(itemId)
        serialize(tabs)
    }

    /**
     * Makes [itemId] **unassigned** by removing it from whichever tab owns it.
     * After this call the item only appears on "All".
     */
    @Synchronized
    fun removeItemFromTab(itemId: Long) {
        val tabs = deserialize().toMutableList()
        var changed = false
        tabs.forEach { changed = changed or it.habitIds.remove(itemId) }
        if (changed) serialize(tabs)
    }

    // -----------------------------------------------------------------------
    // Active-tab persistence
    // -----------------------------------------------------------------------

    /**
     * Persists [tabId] as the tab that should be re-selected on the next
     * launch.  Pass null to indicate the "All" tab.
     */
    @Synchronized
    fun saveActiveTab(tabId: String?) {
        prefs.edit().apply {
            if (tabId == null) remove(KEY_ACTIVE_TAB) else putString(KEY_ACTIVE_TAB, tabId)
        }.apply()
    }

    /**
     * Returns the persisted active tab id, or null if "All" was active (or
     * the saved tab has since been deleted).
     */
    @Synchronized
    fun loadActiveTab(): String? {
        val saved = prefs.getString(KEY_ACTIVE_TAB, null) ?: return null
        return if (getAllTabs().any { it.id == saved }) saved else null
    }

    // -----------------------------------------------------------------------
    // Serialisation
    // -----------------------------------------------------------------------

    private fun deserialize(): List<HabitTab> {
        val json = prefs.getString(KEY_TABS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val idsArray = obj.getJSONArray(FIELD_HABIT_IDS)
                val ids = (0 until idsArray.length())
                    .map { j -> idsArray.getLong(j) }
                    .toMutableSet()
                HabitTab(
                    id = obj.getString(FIELD_ID),
                    name = obj.getString(FIELD_NAME),
                    habitIds = ids
                )
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
        private const val KEY_ACTIVE_TAB = "active_tab"
        private const val FIELD_ID = "id"
        private const val FIELD_NAME = "name"
        private const val FIELD_HABIT_IDS = "habitIds"
    }
}

