/*
 * Copyright (C) 2016-2021 Álvaro Santos Xavier <git@axavier.org>
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

package org.isoron.uhabits.core.ui.screens.habits.show.views

import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGroup
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.ui.views.Theme
import org.isoron.uhabits.core.utils.DateUtils

data class TargetCardState(
    val color: PaletteColor,
    val values: List<Double> = listOf(),
    val targets: List<Double> = listOf(),
    val intervals: List<Int> = listOf(),
    val theme: Theme
)

class TargetCardPresenter {
    companion object {

        /**
         * Sums raw entry values over a moving window of [days] days ending today.
         * For boolean habits YES_MANUAL counts as 1000, everything else as 0.
         * For numerical habits the raw value is used (SKIP counts as 0).
         */
        private fun movingWindowSum(habit: Habit, days: Int): Double {
            val today = DateUtils.getTodayWithOffset()
            val from = today.minus(days - 1)
            val entries = habit.computedEntries.getByInterval(from, today)
            val raw = entries.sumOf { entry ->
                when {
                    habit.isNumerical -> if (entry.value == Entry.SKIP) 0 else maxOf(0, entry.value)
                    entry.value == Entry.YES_MANUAL -> 1000
                    else -> 0
                }
            }
            return raw / 1e3
        }

        fun buildState(
            habit: Habit,
            firstWeekday: Int,
            theme: Theme
        ): TargetCardState {
            val denominator = habit.frequency.denominator

            // Moving window sizes in days matching each label
            // "today" window = 1 day (only shown for daily habits)
            // "week"  window = denominator days (e.g. 7 for weekly habit)
            // "month" window = 30 days
            // "year"  window = 365 days

            val valueToday   = if (denominator <= 1) movingWindowSum(habit, 1)   else 0.0
            val valueThisWeek  = if (denominator <= 7) movingWindowSum(habit, maxOf(denominator, 7)) else 0.0
            val valueThisMonth = movingWindowSum(habit, 30)
            val valueThisYear  = movingWindowSum(habit, 365)

            // Target for each window is simply targetValue scaled to how many
            // full frequency periods fit in that window.
            val targetValue = habit.targetValue
            val dailyTarget = targetValue / denominator  // target per day

            val targetToday   = targetValue                         // 1 period (denom=1)
            val targetThisWeek = when (denominator) {
                7    -> targetValue                                  // exactly 1 period
                else -> dailyTarget * maxOf(denominator, 7)         // scale daily
            }
            val targetThisMonth = when (denominator) {
                30   -> targetValue
                7    -> targetValue * (30.0 / 7)
                else -> dailyTarget * 30
            }
            val targetThisYear = when (denominator) {
                30   -> targetValue * 12
                7    -> targetValue * 52
                else -> dailyTarget * 365
            }

            val values = ArrayList<Double>()
            if (denominator <= 1) values.add(valueToday)
            if (denominator <= 7) values.add(valueThisWeek)
            values.add(valueThisMonth)
            values.add(valueThisYear)

            val targets = ArrayList<Double>()
            if (denominator <= 1) targets.add(targetToday)
            if (denominator <= 7) targets.add(targetThisWeek)
            targets.add(targetThisMonth)
            targets.add(targetThisYear)

            val intervals = ArrayList<Int>()
            if (denominator <= 1) intervals.add(1)
            if (denominator <= 7) intervals.add(7)
            intervals.add(30)
            intervals.add(365)

            return TargetCardState(
                color = habit.color,
                values = values,
                targets = targets,
                intervals = intervals,
                theme = theme
            )
        }

        fun buildState(
            habitGroup: HabitGroup,
            firstWeekday: Int,
            theme: Theme
        ): TargetCardState {
            val maxDen = habitGroup.habitList.maxOfOrNull { habit -> habit.frequency.denominator }
            val isNumerical = habitGroup.habitList.all { it.isNumerical }
            if (maxDen == null || !isNumerical) {
                return TargetCardState(
                    color = habitGroup.color,
                    values = arrayListOf(0.0, 0.0, 0.0, 0.0),
                    targets = arrayListOf(0.0, 0.0, 0.0, 0.0),
                    intervals = arrayListOf(1, 7, 30, 365),
                    theme = theme
                )
            }

            val states = habitGroup.habitList.map { Companion.buildState(it, firstWeekday, theme) }

            val values = states
                .map {
                    val startIdx = it.intervals.indexOf(maxDen)
                    val endIdx = it.intervals.size
                    it.values.subList(startIdx, endIdx)
                }
                .reduce { acc, list -> acc.zip(list) { a, b -> a + b } }

            val targets = states
                .map {
                    val startIdx = it.intervals.indexOf(maxDen)
                    val endIdx = it.intervals.size
                    it.targets.subList(startIdx, endIdx)
                }
                .reduce { acc, list -> acc.zip(list) { a, b -> a + b } }

            val intervals = arrayListOf(1, 7, 30, 365).filter { it >= maxDen }

            return TargetCardState(
                color = habitGroup.color,
                values = values,
                targets = targets,
                intervals = intervals,
                theme = theme
            )
        }
    }
}
