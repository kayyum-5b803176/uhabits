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

/**
 * Represents a single user-defined tab in the habit list.
 *
 * @param id        A unique identifier (UUID string) for this tab.
 * @param name      The display name shown on the tab chip.
 * @param habitIds  The set of habit IDs that belong to this tab.
 */
data class HabitTab(
    val id: String,
    var name: String,
    val habitIds: MutableSet<Long> = mutableSetOf()
)
