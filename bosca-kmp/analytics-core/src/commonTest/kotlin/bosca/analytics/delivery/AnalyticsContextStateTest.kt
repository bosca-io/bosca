package bosca.analytics.delivery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AnalyticsContextStateTest {
    @Test
    fun `user identity changes create a new context while repeated values reuse it`() {
        val state = AnalyticsContextState(config())

        val signedIn = state.snapshot("principal-1")
        assertEquals(signedIn.id, state.snapshot("principal-1").id)

        val switched = state.snapshot("principal-2")
        assertNotEquals(signedIn.id, switched.id)
        assertEquals("principal-2", switched.value.userId)

        val signedOut = state.snapshot(null)
        assertNotEquals(switched.id, signedOut.id)
        assertEquals(null, signedOut.value.userId)
    }

    @Test
    fun `anonymous contexts never retain user identity`() {
        val state = AnalyticsContextState(config(anonymous = true))
        val initial = state.snapshot()

        state.snapshot("principal-1")

        assertEquals(initial, state.snapshot())
    }

    private fun config(anonymous: Boolean = false) = BoscaSinkConfig(
        url = "https://analytics.test",
        appId = "app",
        appVersion = "1",
        clientId = "mobile",
        anonymous = anonymous,
    )
}
