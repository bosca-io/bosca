package bosca.analytics.server

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalyticsCaptureGuardTest {

    @Test
    fun `guard is inactive by default`() = runTest {
        assertFalse(analyticsCaptureGuardActive())
    }

    @Test
    fun `withAnalyticsCaptureGuard activates inside the block`() = runTest {
        var inside = false
        withAnalyticsCaptureGuard {
            inside = analyticsCaptureGuardActive()
        }
        assertTrue(inside)
        assertFalse(analyticsCaptureGuardActive())
    }

    @Test
    fun `nested guards remain active`() = runTest {
        withAnalyticsCaptureGuard {
            assertTrue(analyticsCaptureGuardActive())
            withAnalyticsCaptureGuard {
                assertTrue(analyticsCaptureGuardActive())
            }
            assertTrue(analyticsCaptureGuardActive())
        }
    }
}
