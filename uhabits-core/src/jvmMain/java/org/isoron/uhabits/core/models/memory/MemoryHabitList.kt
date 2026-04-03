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
package org.isoron.uhabits.core.models.memory

import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitMatcher
import org.isoron.uhabits.core.utils.DateUtils.Companion.getTodayWithOffset
import java.util.LinkedList
import java.util.Objects

/**
 * In-memory implementation of [HabitList].
 */
class MemoryHabitList : HabitList {
    private val list = LinkedList<Habit>()

    @get:Synchronized
    override var primaryOrder = Order.BY_POSITION
        set(value) {
            field = value
            comparator = getComposedComparatorByOrder(primaryOrder, secondaryOrder)
            resort()
        }

    @get:Synchronized
    override var secondaryOrder = Order.BY_NAME_ASC
        set(value) {
            field = value
            comparator = getComposedComparatorByOrder(primaryOrder, secondaryOrder)
            resort()
        }

    private var comparator: Comparator<Habit>? =
        getComposedComparatorByOrder(primaryOrder, secondaryOrder)
    private var parent: MemoryHabitList? = null

    override var collapsed: Boolean = false
        set(value) {
            field = value
            val habits = parent?.list ?: list
            habits.forEach { it.collapsed = value }
        }

    constructor() : super()
    constructor(
        matcher: HabitMatcher,
        comparator: Comparator<Habit>?,
        parent: MemoryHabitList
    ) : super(matcher) {
        this.parent = parent
        this.comparator = comparator
        this.groupId = parent.groupId
        primaryOrder = parent.primaryOrder
        secondaryOrder = parent.secondaryOrder
        parent.observable.addListener { loadFromParent() }
        loadFromParent()
    }

    @Synchronized
    @Throws(IllegalArgumentException::class)
    override fun add(habit: Habit) {
        throwIfHasParent()
        require(!list.contains(habit)) { "habit already added" }
        val id = habit.id
        if (id != null && getById(id) != null) throw RuntimeException("duplicate id")
        if (id == null) habit.id = list.size.toLong()
        list.addLast(habit)
        resort()
    }

    @Synchronized
    @Throws(IllegalArgumentException::class)
    override fun add(position: Int, habit: Habit) {
        throwIfHasParent()
        require(!list.contains(habit)) { "habit already added" }
        val id = habit.id
        if (id != null && getById(id) != null) throw RuntimeException("duplicate id")
        if (id == null) habit.id = list.size.toLong()
        list.add(position, habit)
    }

    @Synchronized
    override fun getById(id: Long): Habit? {
        for (h in list) {
            checkNotNull(h.id)
            if (h.id == id) return h
        }
        return null
    }

    @Synchronized
    override fun getByUUID(uuid: String?): Habit? {
        for (h in list) if (Objects.requireNonNull(h.uuid) == uuid) return h
        return null
    }

    @Synchronized
    override fun getByPosition(position: Int): Habit {
        return list[position]
    }

    @Synchronized
    override fun getFiltered(matcher: HabitMatcher?): HabitList {
        return MemoryHabitList(matcher!!, comparator, this)
    }

    private fun getComposedComparatorByOrder(
        firstOrder: Order,
        secondOrder: Order?
    ): Comparator<Habit> {
        val first = getComparatorByOrder(firstOrder)
        val second = if (secondOrder != null) getComparatorByOrder(secondOrder) else null
        return Comparator { h1, h2 ->
            val r = first.compare(h1, h2)
            if (r != 0 || second == null) r else second.compare(h1, h2)
        }
    }

    private fun getComparatorByOrder(order: Order): Comparator<Habit> = when (order) {
        Order.BY_NAME_ASC -> Comparator { h1, h2 -> h1.name.compareTo(h2.name) }
        Order.BY_NAME_DESC -> Comparator { h1, h2 -> h2.name.compareTo(h1.name) }
        Order.BY_COLOR_ASC -> Comparator { h1, h2 -> h1.color.paletteIndex.compareTo(h2.color.paletteIndex) }
        Order.BY_COLOR_DESC -> Comparator { h1, h2 -> h2.color.paletteIndex.compareTo(h1.color.paletteIndex) }
        Order.BY_SCORE_DESC -> Comparator { h1, h2 ->
            val today = getTodayWithOffset()
            h1.scores[today].value.compareTo(h2.scores[today].value)
        }
        Order.BY_SCORE_ASC -> Comparator { h1, h2 ->
            val today = getTodayWithOffset()
            h2.scores[today].value.compareTo(h1.scores[today].value)
        }
        Order.BY_STATUS_DESC -> Comparator { h1, h2 ->
            if (h1.isCompletedToday() != h2.isCompletedToday()) {
                return@Comparator if (h1.isCompletedToday()) -1 else 1
            }
            if (h1.isNumerical != h2.isNumerical) {
                return@Comparator if (h1.isNumerical) -1 else 1
            }
            val today = getTodayWithOffset()
            h2.computedEntries.get(today).value.compareTo(h1.computedEntries.get(today).value)
        }
        Order.BY_STATUS_ASC -> Comparator { h1, h2 ->
            if (h1.isCompletedToday() != h2.isCompletedToday()) {
                return@Comparator if (h1.isCompletedToday()) 1 else -1
            }
            if (h1.isNumerical != h2.isNumerical) {
                return@Comparator if (h1.isNumerical) 1 else -1
            }
            val today = getTodayWithOffset()
            h1.computedEntries.get(today).value.compareTo(h2.computedEntries.get(today).value)
        }
        Order.BY_VIRTUAL_PROGRESS -> Comparator { h1, h2 ->
            h1.virtualProgress.compareTo(h2.virtualProgress)
        }
        Order.BY_POSITION -> Comparator { h1, h2 -> h1.position.compareTo(h2.position) }
        else -> Comparator { h1, h2 -> h1.position.compareTo(h2.position) }
    }

    @Synchronized
    override fun indexOf(h: Habit): Int {
        return list.indexOf(h)
    }

    @Synchronized
    override fun iterator(): Iterator<Habit> {
        return ArrayList(list).iterator()
    }

    @Synchronized
    override fun remove(h: Habit) {
        throwIfHasParent()
        list.remove(h)
        observable.notifyListeners()
    }

    @Synchronized
    override fun removeAt(position: Int) {
        throwIfHasParent()
        list.removeAt(position)
        observable.notifyListeners()
    }

    @Synchronized
    override fun reorder(from: Habit, to: Habit) {
        throwIfHasParent()
        check(!(primaryOrder !== Order.BY_POSITION)) { "cannot reorder automatically sorted list" }
        require(indexOf(from) >= 0) { "list does not contain (from) habit" }
        val toPos = indexOf(to)
        require(toPos >= 0) { "list does not contain (to) habit" }
        list.remove(from)
        list.add(toPos, from)
        var position = 0
        for (h in list) h.position = position++
        observable.notifyListeners()
    }

    @Synchronized
    override fun size(): Int {
        return list.size
    }

    @Synchronized
    override fun update(habits: List<Habit>) {
        resort()
    }

    private fun throwIfHasParent() {
        check(parent == null) {
            "Filtered lists cannot be modified directly. " +
                "You should modify the parent list instead."
        }
    }

    @Synchronized
    private fun loadFromParent() {
        checkNotNull(parent)
        list.clear()
        for (h in parent!!) if (filter.matches(h)) list.add(h)
        resort()
    }

    @Synchronized
    override fun resort() {
        if (comparator != null) list.sortWith(comparator!!)
        observable.notifyListeners()
    }
}
