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
package org.isoron.uhabits.core.models

import org.isoron.uhabits.core.models.Score.Companion.compute
import java.util.TreeMap
import javax.annotation.concurrent.ThreadSafe
import kotlin.math.max
import kotlin.math.min

private const val MAX_SCORE_DAYS = 365 * 2 + 30

@ThreadSafe
class ScoreList {

    private val map = TreeMap<Timestamp, Score>(compareBy { it.unixTime })

    /**
     * Returns the score for a given day. If the timestamp given happens before the first
     * repetition of the habit or after the last computed score, returns a score with value zero.
     */
    @Synchronized
    operator fun get(timestamp: Timestamp): Score {
        return map[timestamp] ?: Score(timestamp, 0.0)
    }

    /**
     * Returns the list of scores that fall within the given interval.
     *
     * There is exactly one score per day in the interval. The endpoints of the interval are
     * included. The list is ordered by timestamp (decreasing). That is, the first score
     * corresponds to the newest timestamp, and the last score corresponds to the oldest timestamp.
     */
    @Synchronized
    fun getByInterval(
        fromTimestamp: Timestamp,
        toTimestamp: Timestamp
    ): List<Score> {
        if (fromTimestamp.isNewerThan(toTimestamp)) return emptyList()
        return map.subMap(fromTimestamp, true, toTimestamp, true)
            .descendingMap()
            .values
            .toList()
    }

    /**
     * Recomputes all scores between the provided [from] and [to] timestamps.
     * Takes into account whether some days of the week are automatically skipped.
     * Caps the stored window to MAX_SCORE_DAYS to prevent unbounded memory growth.
     */
    @Synchronized
    fun recompute(
        frequency: Frequency,
        isNumerical: Boolean,
        skipDays: SkipDays,
        numericalHabitType: NumericalHabitType,
        targetValue: Double,
        computedEntries: EntryList,
        from: Timestamp,
        to: Timestamp
    ) {
        map.clear()
        val cappedFrom = if (from.isOlderThan(to.minus(MAX_SCORE_DAYS))) to.minus(MAX_SCORE_DAYS) else from
        var rollingSum = 0.0
        var numerator = frequency.numerator
        var denominator = frequency.denominator
        val freq = frequency.toDouble()
        val values = computedEntries.getByInterval(cappedFrom, to, skipDays).map { it.value }.toIntArray()
        val isAtMost = numericalHabitType == NumericalHabitType.AT_MOST

        // For non-daily boolean habits, we double the numerator and the denominator to smooth
        // out irregular repetition schedules (for example, weekly habits performed on different
        // days of the week)
        if (!isNumerical && freq < 1.0) {
            numerator *= 2
            denominator *= 2
        }

        var previousValue = if (isNumerical && isAtMost) 1.0 else 0.0
        for (i in values.indices) {
            val offset = values.size - i - 1
            if (isNumerical) {
                rollingSum += max(0, values[offset])
                if (offset + denominator < values.size) {
                    rollingSum -= max(0, values[offset + denominator])
                }

                val normalizedRollingSum = rollingSum / 1000
                if (values[offset] != Entry.SKIP) {
                    val percentageCompleted = if (!isAtMost) {
                        if (targetValue > 0) {
                            min(1.0, normalizedRollingSum / targetValue)
                        } else {
                            1.0
                        }
                    } else {
                        if (targetValue > 0) {
                            (1 - ((normalizedRollingSum - targetValue) / targetValue)).coerceIn(
                                0.0,
                                1.0
                            )
                        } else {
                            if (normalizedRollingSum > 0) 0.0 else 1.0
                        }
                    }

                    previousValue = compute(freq, previousValue, percentageCompleted)
                }
            } else {
                if (values[offset] == Entry.YES_MANUAL) {
                    rollingSum += 1.0
                }
                if (offset + denominator < values.size) {
                    if (values[offset + denominator] == Entry.YES_MANUAL) {
                        rollingSum -= 1.0
                    }
                }
                if (values[offset] != Entry.SKIP) {
                    val percentageCompleted = min(1.0, rollingSum / numerator)
                    previousValue = compute(freq, previousValue, percentageCompleted)
                }
            }
            val timestamp = cappedFrom.plus(i)
            map[timestamp] = Score(timestamp, previousValue)
        }
    }

    @Synchronized
    fun combineFrom(
        habitList: HabitList,
        from: Timestamp,
        to: Timestamp
    ) {
        var current = to
        while (current >= from) {
            val habitScores = habitList
                .filter { !it.isArchived }
                .map { it.scores[current].value }
            val averageScore = if (habitScores.isNotEmpty()) habitScores.average() else 0.0
            map[current] = Score(current, averageScore)
            current = current.minus(1)
        }
    }
}
