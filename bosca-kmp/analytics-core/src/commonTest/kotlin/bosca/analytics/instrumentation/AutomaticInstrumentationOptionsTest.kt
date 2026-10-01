package bosca.analytics.instrumentation

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AutomaticInstrumentationOptionsTest {
    @Test
    fun `automatic instrumentation limits must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            AutomaticInstrumentationOptions(maxErrorsPerMinute = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AutomaticInstrumentationOptions(crashFlushTimeout = Duration.ZERO)
        }
        AutomaticInstrumentationOptions(
            captureUnhandledExceptions = false,
            maxErrorsPerMinute = 1,
            crashFlushTimeout = 1.seconds,
        )
    }
}
