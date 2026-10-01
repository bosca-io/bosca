package bosca.ecommerce.model

import bosca.serialization.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**the per-account redemption limit window each [FrequencyLimit] resolves to. */
class FrequencyLimitTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-06-20T12:00:00Z")

    @Test
    fun `FOREVER spans all redemption history`() {
        assertEquals(now.minusYears(1000), FrequencyLimit.FOREVER.windowStart(now))
    }

    @Test
    fun `DAILY is one day back`() {
        assertEquals(now.minusDays(1), FrequencyLimit.DAILY.windowStart(now))
    }

    @Test
    fun `WEEKLY is one week back`() {
        assertEquals(now.minusWeeks(1), FrequencyLimit.WEEKLY.windowStart(now))
    }

    @Test
    fun `MONTHLY is one month back`() {
        assertEquals(now.minusMonths(1), FrequencyLimit.MONTHLY.windowStart(now))
    }

    @Test
    fun `YEARLY is one year back`() {
        assertEquals(now.minusYears(1), FrequencyLimit.YEARLY.windowStart(now))
    }
}
