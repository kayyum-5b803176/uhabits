package org.isoron.uhabits.core.models.memory

import org.isoron.uhabits.core.models.HabitGroup
import org.isoron.uhabits.core.models.HabitGroupList
import org.isoron.uhabits.core.models.HabitList.Order
import org.isoron.uhabits.core.models.HabitMatcher
import org.isoron.uhabits.core.models.ModelObservable
import java.util.LinkedList
import java.util.Objects

/**
 * In-memory implementation of [HabitGroupList].
 */
class MemoryHabitGroupList : HabitGroupList {
    private val list = LinkedList<HabitGroup>()

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

    private var comparator: Comparator<HabitGroup>? =
        getComposedComparatorByOrder(primaryOrder, secondaryOrder)
    private var parent: MemoryHabitGroupList? = null

    constructor() : super()
    constructor(
        matcher: HabitMatcher,
        comparator: Comparator<HabitGroup>?,
        parent: MemoryHabitGroupList
    ) : super(matcher) {
        this.parent = parent
        this.comparator = comparator
        primaryOrder = parent.primaryOrder
        secondaryOrder = parent.secondaryOrder
        parent.observable.addListener(parentListener)
        loadFromParent()
    }

    private val parentListener = ModelObservable.Listener { loadFromParent() }
    private val groupListeners = mutableMapOf<Long?, ModelObservable.Listener>()

    @Synchronized
    @Throws(IllegalArgumentException::class)
    override fun add(habitGroup: HabitGroup) {
        throwIfHasParent()
        require(!list.contains(habitGroup)) { "habit already added" }
        val id = habitGroup.id
        if (id != null && getById(id) != null) throw RuntimeException("duplicate id")
        if (id == null) habitGroup.id = list.size.toLong()
        list.addLast(habitGroup)
        resort()
    }

    @Synchronized
    override fun getById(id: Long): HabitGroup? {
        for (h in list) {
            checkNotNull(h.id)
            if (h.id == id) return h
        }
        return null
    }

    @Synchronized
    override fun getByUUID(uuid: String?): HabitGroup? {
        for (h in list) if (Objects.requireNonNull(h.uuid) == uuid) return h
        return null
    }

    @Synchronized
    override fun getByPosition(position: Int): HabitGroup {
        return list[position]
    }

    @Synchronized
    override fun getFiltered(matcher: HabitMatcher?): HabitGroupList {
        return MemoryHabitGroupList(matcher!!, comparator, this)
    }

    private fun getComposedComparatorByOrder(
        firstOrder: Order,
        secondOrder: Order?
    ): Comparator<HabitGroup> {
        val first = getComparatorByOrder(firstOrder)
        val second = if (secondOrder != null) getComparatorByOrder(secondOrder) else null
        return Comparator { h1, h2 ->
            val r = first.compare(h1, h2)
            if (r != 0 || second == null) r else second.compare(h1, h2)
        }
    }

    private fun getComparatorByOrder(order: Order): Comparator<HabitGroup> = when (order) {
        Order.BY_NAME_ASC -> Comparator { h1, h2 -> h1.name.compareTo(h2.name) }
        Order.BY_NAME_DESC -> Comparator { h1, h2 -> h2.name.compareTo(h1.name) }
        Order.BY_COLOR_ASC -> Comparator { h1, h2 -> h1.color.paletteIndex.compareTo(h2.color.paletteIndex) }
        Order.BY_COLOR_DESC -> Comparator { h1, h2 -> h2.color.paletteIndex.compareTo(h1.color.paletteIndex) }
        Order.BY_SCORE_DESC -> Comparator { h1, h2 ->
            h1.position.compareTo(h2.position)
        }
        Order.BY_SCORE_ASC -> Comparator { h1, h2 ->
            h2.position.compareTo(h1.position)
        }
        Order.BY_STATUS_DESC -> Comparator { h1, h2 ->
            if (h1.isCompletedToday() != h2.isCompletedToday()) {
                return@Comparator if (h1.isCompletedToday()) -1 else 1
            }
            h1.position.compareTo(h2.position)
        }
        Order.BY_STATUS_ASC -> Comparator { h1, h2 ->
            if (h1.isCompletedToday() != h2.isCompletedToday()) {
                return@Comparator if (h1.isCompletedToday()) 1 else -1
            }
            h2.position.compareTo(h1.position)
        }
        // Groups don't have virtualProgress — fall back to position
        Order.BY_VIRTUAL_PROGRESS, Order.BY_POSITION -> Comparator { h1, h2 ->
            h1.position.compareTo(h2.position)
        }
        else -> Comparator { h1, h2 -> h1.position.compareTo(h2.position) }
    }

    @Synchronized
    override fun indexOf(h: HabitGroup): Int {
        return list.indexOf(h)
    }

    @Synchronized
    override fun iterator(): Iterator<HabitGroup> {
        return ArrayList(list).iterator()
    }

    @Synchronized
    override fun remove(h: HabitGroup) {
        throwIfHasParent()
        list.remove(h)
        observable.notifyListeners()
    }

    @Synchronized
    override fun reorder(from: HabitGroup, to: HabitGroup) {
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
    override fun update(habitGroups: List<HabitGroup>) {
        resort()
    }

    override fun attachHabitsToGroups() {
        for (hgr in list) {
            for (h in hgr.habitList) {
                h.group = hgr
            }
        }
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
        // Remove stale per-group listeners before rebuild
        for ((id, listener) in groupListeners) {
            parent!!.list.find { it.id == id }?.habitList?.observable?.removeListener(listener)
        }
        groupListeners.clear()

        list.clear()
        for (hgr in parent!!) {
            if (filter.matches(hgr)) {
                val filteredHgr = HabitGroup(hgr, filter)
                list.add(filteredHgr)
            }
            // Track one listener per group's habitList so changes propagate
            val listener = ModelObservable.Listener { loadFromParent() }
            groupListeners[hgr.id] = listener
            hgr.habitList.observable.addListener(listener)
        }
        resort()
    }

    @Synchronized
    override fun resort() {
        for (hgr in list) {
            hgr.habitList.primaryOrder = primaryOrder
            hgr.habitList.secondaryOrder = secondaryOrder
            hgr.habitList.resort()
        }
        if (comparator != null) list.sortWith(comparator!!)
        observable.notifyListeners()
    }
}
