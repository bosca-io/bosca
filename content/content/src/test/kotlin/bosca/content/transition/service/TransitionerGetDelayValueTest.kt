package bosca.content.transition.service

import bosca.content.transition.service.Transitioner.Companion.getDelay
import bosca.serialization.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaDuration

@OptIn(ExperimentalTime::class)
class TransitionerGetDelayValueTest {

    @Test
    fun `getDelay returns correct value for epoch time in the future`() {
        val now = Clock.System.now().toEpochMilliseconds()
        val futureEpochMillis = now + 60_000L
        val delay = futureEpochMillis.getDelay()
        assertNotNull(delay)
        val expected = bosca.serialization.OffsetDateTime.now().plus(60_000.milliseconds.toJavaDuration())
        // Allow for small difference due to timing
        val diff = java.time.Duration.between(delay!!.toInstant(), expected.toInstant()).abs()
        assertTrue(diff.toMillis() < 1000, "Delay should be approximately 60 seconds from now, but was off by ${diff.toMillis()}ms")
    }
}

private fun assertNotNull(actual: Any?) {
    if (actual == null) throw AssertionError("Expected non-null but was null")
}
