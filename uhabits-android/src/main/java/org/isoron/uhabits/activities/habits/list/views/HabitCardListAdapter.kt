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
package org.isoron.uhabits.activities.habits.list.views

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView.Adapter
import org.isoron.uhabits.activities.habits.list.MAX_CHECKMARK_COUNT
import org.isoron.uhabits.activities.habits.list.views.HabitCardView
import org.isoron.uhabits.activities.habits.list.views.HabitGroupCardView
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGroup
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitMatcher
import org.isoron.uhabits.core.models.ModelObservable
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.list.HabitCardListCache
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsMenuBehavior
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsSelectionMenuBehavior
import org.isoron.uhabits.core.utils.MidnightTimer
import org.isoron.uhabits.inject.ActivityScope
import java.util.LinkedList
import javax.inject.Inject

// ---------------------------------------------------------------------------
// Tab-filter position mapping
// ---------------------------------------------------------------------------
// When a tab filter is active, `filteredPositions` holds the subset of real
// cache indices that match the tab's habitIds. All public position-based APIs
// translate through virtualToReal() before delegating to the cache.
// When no filter is active (activeTabId == null), virtualToReal() is a no-op.

/**
 * Provides data that backs a [HabitCardListView].
 *
 *
 * The data is fetched and cached by a [HabitCardListCache]. This adapter
 * also holds a list of items that have been selected.
 */
@SuppressLint("NotifyDataSetChanged")
@ActivityScope
class HabitCardListAdapter @Inject constructor(
    private val cache: HabitCardListCache,
    private val preferences: Preferences,
    private val midnightTimer: MidnightTimer
) : Adapter<HabitCardViewHolder?>(),
    HabitCardListCache.Listener,
    MidnightTimer.MidnightListener,
    ListHabitsMenuBehavior.Adapter,
    ListHabitsSelectionMenuBehavior.Adapter {
    val observable: ModelObservable = ModelObservable()
    private var listView: HabitCardListView? = null
    val selectedHabits: LinkedList<Habit> = LinkedList()
    val selectedHabitGroups: LinkedList<HabitGroup> = LinkedList()

    // ------------------------------------------------------------------
    // Tab filter — DB-backed via Habit.tabId / HabitGroup.tabId
    // ------------------------------------------------------------------

    /**
     * When non-null, only habits/groups whose tabId equals this value are shown.
     * Child habits are shown automatically because they carry the same tabId
     * as their parent group when assigned via moveItemToTab().
     */
    @SuppressLint("NotifyDataSetChanged")
    var activeTabId: String? = null
        set(value) {
            field = value
            rebuildFilteredPositions()
            notifyDataSetChanged()
            observable.notifyListeners()
        }

    /**
     * IDs of tabs marked as private. Items belonging to these tabs are hidden
     * from the "All" tab so private habits never surface unguarded.
     * Update this set whenever tabs are created, deleted, or their privacy
     * flag changes.
     */
    @SuppressLint("NotifyDataSetChanged")
    var privateTabIds: Set<String> = emptySet()
        set(value) {
            field = value
            rebuildFilteredPositions()
            notifyDataSetChanged()
            observable.notifyListeners()
        }

    /**
     * Private tab IDs the user has authenticated for on the "All" tab.
     * Items whose tabId is in this set ARE shown on All (with a purple dot).
     * Items in [privateTabIds] but NOT here remain hidden.
     * Cleared whenever the user leaves the All tab or the app is backgrounded.
     */
    @SuppressLint("NotifyDataSetChanged")
    var unlockedPrivateTabIds: Set<String> = emptySet()
        set(value) {
            field = value
            rebuildFilteredPositions()
            notifyDataSetChanged()
            observable.notifyListeners()
        }

    /** True whenever any position-based filtering is needed. */
    private val isFilterActive: Boolean
        get() = activeTabId != null || privateTabIds.isNotEmpty() || unlockedPrivateTabIds.isNotEmpty()

    /** Sorted list of real cache positions that pass the active filter. */
    private var filteredPositions: List<Int> = emptyList()

    private fun rebuildFilteredPositions() {
        if (!isFilterActive) {
            filteredPositions = emptyList()
            return
        }

        val tabId = activeTabId

        // Build a map of groupId -> tabId for all groups in the cache so child
        // habits can inherit their parent's tab assignment in O(1).
        val groupTabIds = mutableMapOf<Long, String?>()
        for (i in 0 until cache.itemCount) {
            val group = cache.getHabitGroupByPosition(i)
            if (group?.id != null) groupTabIds[group.id!!] = group.tabId
        }

        val result = mutableListOf<Int>()
        for (i in 0 until cache.itemCount) {
            val habit = cache.getHabitByPosition(i)
            if (habit != null) {
                // Child habit: match if own tabId matches OR parent group's tabId matches
                val effectiveTabId = if (habit.groupId != null)
                    groupTabIds[habit.groupId] ?: habit.tabId
                else
                    habit.tabId

                val passes = if (tabId != null) {
                    // Specific tab: show only items assigned to this tab
                    effectiveTabId == tabId
                } else {
                    // "All" tab: hide private items unless user authenticated for them
                    if (effectiveTabId == null) true
                    else if (!privateTabIds.contains(effectiveTabId)) true
                    else unlockedPrivateTabIds.contains(effectiveTabId)
                }
                if (passes) result.add(i)
            } else {
                val group = cache.getHabitGroupByPosition(i)
                if (group != null) {
                    val passes = if (tabId != null) {
                        group.tabId == tabId
                    } else {
                        // "All" tab: hide private groups unless user authenticated for them
                        if (group.tabId == null) true
                        else if (!privateTabIds.contains(group.tabId)) true
                        else unlockedPrivateTabIds.contains(group.tabId)
                    }
                    if (passes) result.add(i)
                }
            }
        }
        filteredPositions = result
    }

    private fun virtualToReal(virtualPos: Int): Int =
        if (!isFilterActive) virtualPos else filteredPositions[virtualPos]

    override fun atMidnight() {
        cache.refreshAllHabits()
    }

    fun cancelRefresh() {
        cache.cancelTasks()
    }

    fun hasNoHabit(): Boolean {
        return cache.hasNoHabit()
    }

    fun hasNoHabitGroup(): Boolean {
        return cache.hasNoHabitGroup()
    }

    /**
     * Sets all items as not selected.
     */
    @SuppressLint("NotifyDataSetChanged")
    override fun clearSelection() {
        selectedHabits.clear()
        selectedHabitGroups.clear()
        notifyDataSetChanged()
        observable.notifyListeners()
    }

    override fun getSelectedHabits(): List<Habit> {
        return ArrayList(selectedHabits)
    }

    override fun getSelectedHabitGroups(): List<HabitGroup> {
        return ArrayList(selectedHabitGroups)
    }

    /**
     * Returns the item that occupies a certain position on the list
     *
     * @param position position of the item
     * @return the item at given position or null if position is invalid
     */
    @Deprecated("")
    fun getItem(position: Int): Habit? {
        return cache.getHabitByPosition(position)
    }

    fun getHabit(position: Int): Habit? {
        return cache.getHabitByPosition(virtualToReal(position))
    }

    fun getHabitGroup(position: Int): HabitGroup? {
        return cache.getHabitGroupByPosition(virtualToReal(position))
    }

    override fun getItemCount(): Int =
        if (!isFilterActive) cache.itemCount else filteredPositions.size

    override fun getItemId(position: Int): Long {
        return cache.getIdByPosition(virtualToReal(position))!!
    }

    /**
     * Returns whether list of selected items is empty.
     *
     * @return true if selection is empty, false otherwise
     */
    val isSelectionEmpty: Boolean
        get() = selectedHabits.isEmpty() && selectedHabitGroups.isEmpty()
    val isSortable: Boolean
        get() = cache.primaryOrder == HabitList.Order.BY_POSITION

    /**
     * Notify the adapter that it has been attached to a ListView.
     */
    fun onAttached() {
        cache.onAttached()
        midnightTimer.addListener(this)
    }

    /**
     * When true (user is on "All" tab), a dot is shown on cards that belong to
     * any tab.  Set to false on any named tab since it adds no information there.
     */
    var showTabDot: Boolean = false

    override fun onBindViewHolder(
        holder: HabitCardViewHolder,
        position: Int
    ) {
        if (listView == null) return
        val realPos = virtualToReal(position)
        val habit = cache.getHabitByPosition(realPos)
        if (habit != null) {
            val score = cache.getScore(habit.id!!)
            val checkmarks = cache.getCheckmarks(habit.id!!)
            val notes = cache.getNotes(habit.id!!)
            val selected = selectedHabits.contains(habit)
            val cardView = listView!!.bindCardView(holder, habit, score, checkmarks, notes, selected)
            (cardView as? HabitCardView)?.also {
                it.showTabDot  = showTabDot
                it.isPrivateTab = habit.tabId != null && unlockedPrivateTabIds.contains(habit.tabId)
            }
        } else {
            val habitGroup = cache.getHabitGroupByPosition(realPos)
            val score = cache.getScore(habitGroup!!.id!!)
            val selected = selectedHabitGroups.contains(habitGroup)
            val cardView = listView!!.bindGroupCardView(holder, habitGroup, score, selected)
            (cardView as? HabitGroupCardView)?.also {
                it.showTabDot  = showTabDot
                it.isPrivateTab = habitGroup.tabId != null && unlockedPrivateTabIds.contains(habitGroup.tabId)
            }
        }
    }

    override fun onViewAttachedToWindow(holder: HabitCardViewHolder) {
        listView!!.attachCardView(holder)
    }

    override fun onViewDetachedFromWindow(holder: HabitCardViewHolder) {
        listView!!.detachCardView(holder)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): HabitCardViewHolder {
        if (viewType == 0) {
            val view = listView!!.createHabitCardView()
            return HabitCardViewHolder(view, null)
        } else {
            val view = listView!!.createHabitGroupCardView()
            return HabitCardViewHolder(null, view)
        }
    }

    // function to override getItemViewType and return the type of the view. The view can either be a HabitCardView or a HabitGroupCardView
    override fun getItemViewType(position: Int): Int {
        return if (cache.getHabitByPosition(virtualToReal(position)) != null) {
            0
        } else {
            1
        }
    }

    /**
     * Notify the adapter that it has been detached from a ListView.
     */
    fun onDetached() {
        cache.onDetached()
        midnightTimer.removeListener(this)
    }

    override fun onItemChanged(position: Int) {
        if (isFilterActive) {
            rebuildFilteredPositions()
            notifyDataSetChanged()
        } else {
            notifyItemChanged(position)
        }
        observable.notifyListeners()
    }

    override fun onItemInserted(position: Int) {
        if (isFilterActive) {
            rebuildFilteredPositions()
            notifyDataSetChanged()
        } else {
            notifyItemInserted(position)
        }
        observable.notifyListeners()
    }

    override fun onItemMoved(oldPosition: Int, newPosition: Int) {
        if (isFilterActive) {
            rebuildFilteredPositions()
            notifyDataSetChanged()
        } else {
            notifyItemMoved(oldPosition, newPosition)
        }
        observable.notifyListeners()
    }

    override fun onItemRemoved(position: Int) {
        if (isFilterActive) {
            rebuildFilteredPositions()
            notifyDataSetChanged()
        } else {
            notifyItemRemoved(position)
        }
        observable.notifyListeners()
    }

    override fun onRefreshFinished() {
        if (isFilterActive) {
            rebuildFilteredPositions()
            notifyDataSetChanged()
        }
        observable.notifyListeners()
    }

    /**
     * Removes a list of habits from the adapter.
     *
     *
     * Note that this only has effect on the adapter cache. The database is not
     * modified, and the change is lost when the cache is refreshed. This method
     * is useful for making the ListView more responsive: while we wait for the
     * database operation to finish, the cache can be modified to reflect the
     * changes immediately.
     *
     * @param selected list of habits to be removed
     */
    override fun performRemove(selected: List<Habit>) {
        for (habit in selected) cache.remove(habit.id!!)
    }

    override fun performRemoveHabitGroup(selected: List<HabitGroup>) {
        for (hgr in selected) cache.remove(hgr.id!!)
    }

    /**
     * Changes the order of habits on the adapter.
     *
     *
     * Note that this only has effect on the adapter cache. The database is not
     * modified, and the change is lost when the cache is refreshed. This method
     * is useful for making the ListView more responsive: while we wait for the
     * database operation to finish, the cache can be modified to reflect the
     * changes immediately.
     *
     * @param from the habit that should be moved
     * @param to   the habit that currently occupies the desired position
     */
    fun performReorder(from: Int, to: Int) {
        cache.reorder(virtualToReal(from), virtualToReal(to))
    }

    override fun refresh() {
        cache.refreshAllHabits()
    }

    override fun setFilter(matcher: HabitMatcher) {
        cache.setFilter(matcher)
    }

    /**
     * Sets the HabitCardListView that this adapter will provide data for.
     *
     *
     * This object will be used to generated new HabitCardViews, upon demand.
     *
     * @param listView the HabitCardListView associated with this adapter
     */
    fun setListView(listView: HabitCardListView?) {
        this.listView = listView
    }

    override var primaryOrder: HabitList.Order
        get() = cache.primaryOrder
        set(value) {
            cache.primaryOrder = value
            preferences.defaultPrimaryOrder = value
        }

    override var secondaryOrder: HabitList.Order
        get() = cache.secondaryOrder
        set(value) {
            cache.secondaryOrder = value
            preferences.defaultSecondaryOrder = value
        }

    /**
     * Selects or deselects the item at a given position.
     *
     * @param position position of the item to be toggled
     */
    @SuppressLint("NotifyDataSetChanged")
    fun toggleSelection(position: Int) {
        val realPos = virtualToReal(position)
        val h = cache.getHabitByPosition(realPos)
        val hgr = cache.getHabitGroupByPosition(realPos)
        if (h != null) {
            val k = selectedHabits.indexOf(h)
            if (k < 0) selectedHabits.add(h) else selectedHabits.remove(h)
            notifyDataSetChanged()
        } else if (hgr != null) {
            val k = selectedHabitGroups.indexOf(hgr)
            if (k < 0) selectedHabitGroups.add(hgr) else selectedHabitGroups.remove(hgr)
            notifyDataSetChanged()
        }
    }

    init {
        cache.setListener(this)
        cache.setCheckmarkCount(
            MAX_CHECKMARK_COUNT
        )
        cache.secondaryOrder = preferences.defaultSecondaryOrder
        cache.primaryOrder = preferences.defaultPrimaryOrder
        setHasStableIds(true)
    }
}
