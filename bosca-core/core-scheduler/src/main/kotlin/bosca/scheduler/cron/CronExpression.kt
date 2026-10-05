package bosca.scheduler.cron

import bosca.serialization.LocalDateTime
import bosca.serialization.OffsetDateTime
import java.time.DateTimeException

/**
 * A 5-field cron expression parser and evaluator.
 *
 * Format: minute hour dayOfMonth month dayOfWeek
 *
 * Fields:
 * - minute: 0-59
 * - hour: 0-23
 * - dayOfMonth: 1-31
 * - month: 1-12 or JAN-DEC
 * - dayOfWeek: 0-7 or SUN-SAT (0 and 7 are Sunday)
 *
 * Special characters:
 * - `*` matches all values
 * - `,` separates multiple values (e.g., 1,3,5)
 * - `-` defines a range (e.g., 1-5)
 * - `/` defines a step (e.g., *\/15 for every 15)
 */
class CronExpression private constructor(
    private val expression: String,
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>
) {

    companion object {
        private val MONTH_NAMES = mapOf(
            "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4,
            "MAY" to 5, "JUN" to 6, "JUL" to 7, "AUG" to 8,
            "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12
        )

        private val DAY_NAMES = mapOf(
            "SUN" to 0, "MON" to 1, "TUE" to 2, "WED" to 3,
            "THU" to 4, "FRI" to 5, "SAT" to 6
        )

        /**
         * Parse a cron expression string.
         *
         * @param expression The cron expression in 6-field format
         * @return A CronExpression instance
         * @throws IllegalArgumentException if the expression is invalid
         */
        fun parse(expression: String): CronExpression {
            val parts = expression.trim().split("\\s+".toRegex())
            if (parts.size != 5) {
                throw IllegalArgumentException(
                    "Cron expression must have 5 fields (minute hour dayOfMonth month dayOfWeek), got ${parts.size}"
                )
            }

            val minutes = parseField(parts[0], 0, 59, "minutes")
            val hours = parseField(parts[1], 0, 23, "hours")
            val daysOfMonth = parseField(parts[2], 1, 31, "days of month")
            val months = parseField(parts[3], 1, 12, "months", MONTH_NAMES)
            val daysOfWeek = parseField(parts[4], 0, 7, "days of week", DAY_NAMES)
                .map { if (it == 7) 0 else it } // Normalize Sunday (7 -> 0)
                .toSet()

            return CronExpression(expression, minutes, hours, daysOfMonth, months, daysOfWeek)
        }

        /**
         * Try to parse a cron expression, returning null if invalid.
         */
        fun tryParse(expression: String): CronExpression? {
            return try {
                parse(expression)
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        /**
         * Validate a cron expression and return an error message if invalid.
         */
        fun validate(expression: String): String? {
            return try {
                parse(expression)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
        }

        private fun parseField(
            field: String,
            min: Int,
            max: Int,
            fieldName: String,
            names: Map<String, Int> = emptyMap()
        ): Set<Int> {
            val result = mutableSetOf<Int>()

            for (part in field.uppercase().split(",")) {
                when {
                    part == "*" -> result.addAll(min..max)
                    part.contains("/") -> {
                        val (range, stepStr) = part.split("/", limit = 2)
                        val step = stepStr.toIntOrNull()
                            ?: throw IllegalArgumentException("Invalid step value '$stepStr' in $fieldName")
                        if (step <= 0) {
                            throw IllegalArgumentException("Step must be positive in $fieldName")
                        }

                        val (start, end) = when {
                            range == "*" -> min to max
                            range.contains("-") -> parseRange(range, min, max, fieldName, names)
                            else -> {
                                val value = parseValue(range, min, max, fieldName, names)
                                value to max
                            }
                        }

                        var current = start
                        while (current <= end) {
                            result.add(current)
                            current += step
                        }
                    }
                    part.contains("-") -> {
                        val (start, end) = parseRange(part, min, max, fieldName, names)
                        result.addAll(start..end)
                    }
                    else -> {
                        result.add(parseValue(part, min, max, fieldName, names))
                    }
                }
            }

            return result
        }

        private fun parseRange(
            range: String,
            min: Int,
            max: Int,
            fieldName: String,
            names: Map<String, Int>
        ): Pair<Int, Int> {
            val parts = range.split("-", limit = 2)
            if (parts.size != 2) {
                throw IllegalArgumentException("Invalid range '$range' in $fieldName")
            }
            val start = parseValue(parts[0], min, max, fieldName, names)
            val end = parseValue(parts[1], min, max, fieldName, names)
            if (start > end) {
                throw IllegalArgumentException("Range start ($start) is greater than end ($end) in $fieldName")
            }
            return start to end
        }

        private fun parseValue(
            value: String,
            min: Int,
            max: Int,
            fieldName: String,
            names: Map<String, Int>
        ): Int {
            val trimmed = value.trim()

            // Try named value first
            names[trimmed]?.let { return it }

            // Try numeric value
            val numeric = trimmed.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid value '$value' in $fieldName")

            if (numeric < min || numeric > max) {
                throw IllegalArgumentException("Value $numeric out of range ($min-$max) in $fieldName")
            }

            return numeric
        }
    }

    /**
     * Get the next execution time after the given instant.
     *
     * @param after The instant to search from
     * @return The next execution instant, or null if none found within 4 years
     */
    fun nextExecution(after: OffsetDateTime): OffsetDateTime? {
        var current = after.toLocalDateTime()
        val offset = after.offset

        // Start from the next minute
        current = current.plusMinutes(1).withSecond(0).withNano(0)

        // Search up to 4 years ahead
        val maxIterations = 4 * 366 * 24 * 60 * 60 // ~4 years in seconds
        var iterations = 0

        while (iterations < maxIterations) {
            // Find matching month
            if (current.month.value !in months) {
                current = nextMonth(current)
                iterations++
                continue
            }

            // Check day of month and day of week
            val dayOfWeekValue = current.dayOfWeek.ordinal // 0=Monday, 6=Sunday
            val cronDayOfWeek = (dayOfWeekValue + 1) % 7 // Convert to cron format (0=Sunday)

            if (current.dayOfMonth !in daysOfMonth || cronDayOfWeek !in daysOfWeek) {
                current = nextDay(current)
                iterations++
                continue
            }

            // Find matching hour
            if (current.hour !in hours) {
                current = nextHour(current)
                iterations++
                continue
            }

            // Find matching minute
            if (current.minute !in minutes) {
                current = nextMinute(current)
                iterations++
                continue
            }

            return current.toInstant(offset).atOffset(offset)
        }

        return null // No match found within reasonable time
    }

    /**
     * Get the next N execution times after the given instant.
     */
    fun nextExecutions(after: OffsetDateTime, count: Int): List<OffsetDateTime> {
        val results = mutableListOf<OffsetDateTime>()
        var current = after

        repeat(count) {
            val next = nextExecution(current) ?: return results
            results.add(next)
            current = next
        }

        return results
    }

    private fun nextMonth(current: LocalDateTime): LocalDateTime {
        val nextMonth = months.filter { it > current.month.value }.minOrNull()
        return if (nextMonth != null) {
            LocalDateTime.of(current.year, nextMonth, 1, 0, 0, 0)
        } else {
            val firstMonth = months.minOrNull() ?: 1
            LocalDateTime.of(current.year + 1, firstMonth, 1, 0, 0, 0)
        }
    }

    private fun nextDay(current: LocalDateTime): LocalDateTime {
        val nextDayOfMonth = try {
            LocalDateTime.of(current.year, current.month.value, current.dayOfMonth + 1,0, 0, 0)
        } catch (_: DateTimeException) {
            nextMonth(current)
        }
        return nextDayOfMonth
    }

    private fun nextHour(current: LocalDateTime): LocalDateTime {
        val nextHour = hours.filter { it > current.hour }.minOrNull()
        return if (nextHour != null) {
            LocalDateTime.of(
                current.year, current.month.value, current.dayOfMonth,
                nextHour, 0, 0
            )
        } else {
            val firstHour = hours.minOrNull() ?: 0
            val nextDay = nextDay(current)
            LocalDateTime.of(
                nextDay.year, nextDay.month.value, nextDay.dayOfMonth,
                firstHour, 0, 0
            )
        }
    }

    private fun nextMinute(current: LocalDateTime): LocalDateTime {
        val nextMinute = minutes.filter { it > current.minute }.minOrNull()
        return if (nextMinute != null) {
            LocalDateTime.of(
                current.year, current.month.value, current.dayOfMonth,
                current.hour, nextMinute
            )
        } else {
            val firstMinute = minutes.minOrNull() ?: 0
            val nextHourTime = nextHour(current)
            LocalDateTime.of(
                nextHourTime.year, nextHourTime.month.value, nextHourTime.dayOfMonth,
                nextHourTime.hour, firstMinute
            )
        }
    }

    override fun toString(): String = expression
}
