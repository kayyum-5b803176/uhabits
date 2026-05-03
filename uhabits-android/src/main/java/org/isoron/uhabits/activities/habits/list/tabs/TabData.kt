package org.isoron.uhabits.activities.habits.list.tabs

/**
 * Represents a single user-defined tab.
 *
 * @param id       UUID string — primary key in the `tabs` DB table.
 * @param name     Display name shown on the tab chip.
 * @param position Sort order (ascending) within the tab bar.
 */
data class HabitTab(
    val id: String,
    var name: String,
    val position: Int = 0,
    val isPrivate: Boolean = false
)
