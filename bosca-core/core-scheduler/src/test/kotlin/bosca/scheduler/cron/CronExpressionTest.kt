package bosca.scheduler.cron

import bosca.serialization.OffsetDateTime
import kotlinx.datetime.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CronExpressionTest {

    @Test
    fun `parse valid cron expression - every minute`() {
        val cron = CronExpression.parse("* * * * *")
        assertNotNull(cron)
    }

    @Test
    fun `parse valid cron expression - every hour`() {
        val cron = CronExpression.parse("0 * * * *")
        assertNotNull(cron)
    }

    @Test
    fun `parse valid cron expression - daily at midnight`() {
        val cron = CronExpression.parse("0 0 * * *")
        assertNotNull(cron)
    }

    @Test
    fun `parse valid cron expression - every 15 minutes`() {
        val cron = CronExpression.parse("*/15 * * * *")
        assertNotNull(cron)
    }

    @Test
    fun `parse valid cron expression - every Sunday at midnight`() {
        val cron = CronExpression.parse("0 0 * * 0")
        assertNotNull(cron)
    }

    @Test
    fun `parse valid cron expression - with month names`() {
        val cron = CronExpression.parse("0 0 1 JAN *")
        assertNotNull(cron)
    }

    @Test
    fun `parse valid cron expression - with day names`() {
        val cron = CronExpression.parse("0 0 * * MON")
        assertNotNull(cron)
    }

    @Test
    fun `parse valid cron expression - with ranges`() {
        val cron = CronExpression.parse("0 9-17 * * MON-FRI")
        assertNotNull(cron)
    }

    @Test
    fun `parse valid cron expression - with lists`() {
        val cron = CronExpression.parse("0 9,12,18 * * *")
        assertNotNull(cron)
    }

    @Test
    fun `parse invalid cron expression - too few fields`() {
        assertFailsWith<IllegalArgumentException> {
            CronExpression.parse("* * * *")
        }
    }

    @Test
    fun `parse invalid cron expression - too many fields`() {
        assertFailsWith<IllegalArgumentException> {
            CronExpression.parse("* * * * * *")
        }
    }

    @Test
    fun `parse invalid cron expression - invalid second value`() {
        assertFailsWith<IllegalArgumentException> {
            CronExpression.parse("60 * * * *")
        }
    }

    @Test
    fun `parse invalid cron expression - invalid minute value`() {
        assertFailsWith<IllegalArgumentException> {
            CronExpression.parse("60 * * * *")
        }
    }

    @Test
    fun `parse invalid cron expression - invalid hour value`() {
        assertFailsWith<IllegalArgumentException> {
            CronExpression.parse("0 24 * * *")
        }
    }

    @Test
    fun `parse invalid cron expression - invalid day of month`() {
        assertFailsWith<IllegalArgumentException> {
            CronExpression.parse("0 0 32 * *")
        }
    }

    @Test
    fun `parse invalid cron expression - invalid month value`() {
        assertFailsWith<IllegalArgumentException> {
            CronExpression.parse("0 0 * 13 *")
        }
    }

    @Test
    fun `parse invalid cron expression - invalid day of week`() {
        assertFailsWith<IllegalArgumentException> {
            CronExpression.parse("0 0 * * 8")
        }
    }

    @Test
    fun `validate returns null for valid expression`() {
        val error = CronExpression.validate("0 * * * *")
        assertNull(error)
    }

    @Test
    fun `validate returns error message for invalid expression`() {
        val error = CronExpression.validate("invalid")
        assertNotNull(error)
    }

    @Test
    fun `tryParse returns null for invalid expression`() {
        val cron = CronExpression.tryParse("invalid")
        assertNull(cron)
    }

    @Test
    fun `tryParse returns CronExpression for valid expression`() {
        val cron = CronExpression.tryParse("0 * * * *")
        assertNotNull(cron)
    }

    @Test
    fun `nextExecution returns correct time for every minute`() {
        val cron = CronExpression.parse("* * * * *")
        val now = OffsetDateTime.parse("2024-01-15T10:30:45Z")
        val next = cron.nextExecution(now)

        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime()
        assertEquals(10, nextDateTime.hour)
        assertEquals(31, nextDateTime.minute)
        assertEquals(0, nextDateTime.second)
    }

    @Test
    fun `nextExecution returns correct time for every hour at minute 0`() {
        val cron = CronExpression.parse("0 * * * *")
        val now = OffsetDateTime.parse("2024-01-15T10:30:00Z")
        val next = cron.nextExecution(now)

        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime()
        assertEquals(11, nextDateTime.hour)
        assertEquals(0, nextDateTime.minute)
        assertEquals(0, nextDateTime.second)
    }

    @Test
    fun `nextExecution returns correct time for daily at midnight`() {
        val cron = CronExpression.parse("0 0 * * *")
        val now = OffsetDateTime.parse("2024-01-15T10:30:00Z")
        val next = cron.nextExecution(now)

        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime()
        assertEquals(16, nextDateTime.dayOfMonth)
        assertEquals(0, nextDateTime.hour)
        assertEquals(0, nextDateTime.minute)
        assertEquals(0, nextDateTime.second)
    }

    @Test
    fun `nextExecution returns correct time for every 15 minutes`() {
        val cron = CronExpression.parse("*/15 * * * *")
        val now = OffsetDateTime.parse("2024-01-15T10:07:00Z")
        val next = cron.nextExecution(now)

        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime()
        assertEquals(10, nextDateTime.hour)
        assertEquals(15, nextDateTime.minute)
        assertEquals(0, nextDateTime.second)
    }

    @Test
    fun `nextExecution handles timezone correctly`() {
        val cron = CronExpression.parse("0 9 * * *") // 9 AM daily
        val now = OffsetDateTime.parse("2024-01-15T12:00:00Z") // 12:00 UTC = 07:00 EST
        val next = cron.nextExecution(now)

        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime()
        assertEquals(9, nextDateTime.hour)
    }

    @Test
    fun `nextExecutions returns correct number of results`() {
        val cron = CronExpression.parse("0 * * * *")
        val now = OffsetDateTime.parse("2024-01-15T10:30:00Z")
        val nextRuns = cron.nextExecutions(now, 5)

        assertEquals(5, nextRuns.size)

        // Verify the times are in order
        for (i in 0 until nextRuns.size - 1) {
            assertTrue(nextRuns[i] < nextRuns[i + 1])
        }
    }

    @Test
    fun `nextExecution with specific day of week`() {
        // Every Monday at 9 AM
        val cron = CronExpression.parse("0 9 * * 1")
        // January 15, 2024 was a Monday
        val now = OffsetDateTime.parse("2024-01-15T10:00:00Z") // Already past 9 AM on Monday
        val next = cron.nextExecution(now)

        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime()
        // Should be next Monday (January 22)
        assertEquals(22, nextDateTime.dayOfMonth)
        assertEquals(9, nextDateTime.hour)
    }

    @Test
    fun `nextExecution with first day of month`() {
        val cron = CronExpression.parse("0 0 1 * *")
        val now = OffsetDateTime.parse("2024-01-15T10:00:00Z")
        val next = cron.nextExecution(now)

        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime()
        assertEquals(1, nextDateTime.dayOfMonth)
        assertEquals(2, nextDateTime.month.value) // February
    }

    @Test
    fun `toString returns original expression`() {
        val expression = "0 * * * *"
        val cron = CronExpression.parse(expression)
        assertEquals(expression, cron.toString())
    }

    @Test
    fun `parse handles Sunday as both 0 and 7`() {
        val cron0 = CronExpression.parse("0 0 * * 0")
        val cron7 = CronExpression.parse("0 0 * * 7")

        val now = OffsetDateTime.parse("2024-01-15T10:00:00Z") // Monday
        val next0 = cron0.nextExecution(now)
        val next7 = cron7.nextExecution(now)

        // Both should give the same next Sunday
        assertEquals(next0, next7)
    }

    @Test
    fun `parse handles step values with range`() {
        val cron = CronExpression.parse("0 8-18/2 * * *") // Every 2 hours from 8 to 18
        val now = OffsetDateTime.parse("2024-01-15T09:00:00Z")
        val next = cron.nextExecution(now)

        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime()
        assertEquals(10, nextDateTime.hour)
    }
}
