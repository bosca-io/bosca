package bosca.analytics.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalyticsContextElementTest {

    @Test
    fun `typed identity is inherited and inner overrides are restored after failure`() = runTest {
        val outer = AnalyticsContext(appId = "app", appVersion = "42", installationId = "install",
            sessionId = "session", userId = "user", extras = mapOf("jobId" to "job"))
        withAnalyticsContext(outer) {
            assertEquals(outer, analyticsContext())
            kotlin.test.assertFailsWith<IllegalStateException> {
                withAnalyticsContext(AnalyticsContext(sessionId = "inner", extras = mapOf("jobId" to "inner-job"))) {
                    assertEquals(outer.copy(sessionId = "inner", extras = mapOf("jobId" to "inner-job")), analyticsContext())
                    error("failed")
                }
            }
            assertEquals(outer, analyticsContext())
            withAnalyticsContext("session_id" to null) {
                assertNull(analyticsContext().sessionId)
                assertEquals(mapOf("session_id" to null), analyticsContext().extras.filterKeys { it == "session_id" })
                assertEquals(outer.appId, analyticsContext().appId)
            }
        }
        assertEquals(AnalyticsContext(), analyticsContext())
    }

    @Test
    fun `typed context round trips identities and preserves additional null entries`() {
        val context = AnalyticsContext("app", "42", "install", "session", "user", mapOf("jobId" to "job", "removed" to null))
        assertEquals(context, AnalyticsContext.fromEntries(context.toMap()))
        assertEquals(AnalyticsContext(), AnalyticsContext.fromEntries(emptyMap()))
        assertEquals("session", context.copy(extras = mapOf("session_id" to "stale")).toMap()["session_id"])
        kotlin.test.assertFailsWith<ClassCastException> { AnalyticsContext.fromEntries(mapOf("session_id" to 42)) }
    }

    @Test
    fun `analyticsContext is empty by default`() = runTest {
        assertEquals(emptyMap(), analyticsContext().toMap())
    }

    @Test
    fun `withAnalyticsContext attaches entries to the current scope`() = runTest {
        val seen = withAnalyticsContext("jobId" to "j-1", "queue" to "default") {
            analyticsContext().toMap()
        }
        assertEquals(mapOf("jobId" to "j-1", "queue" to "default"), seen)
    }

    @Test
    fun `nested withAnalyticsContext composes entries with inner overriding outer`() = runTest {
        val seen = withAnalyticsContext("tenant" to "acme", "queue" to "default") {
            withAnalyticsContext("jobId" to "j-1", "queue" to "override") {
                analyticsContext().toMap()
            }
        }
        assertEquals(
            mapOf("tenant" to "acme", "queue" to "override", "jobId" to "j-1"),
            seen,
        )
    }

    @Test
    fun `entries from outer scope are restored after inner scope returns`() = runTest {
        val outerSeen = withAnalyticsContext("tenant" to "acme") {
            withAnalyticsContext("jobId" to "j-1") { /* inner */ }
            analyticsContext().toMap()
        }
        assertEquals(mapOf("tenant" to "acme"), outerSeen)
    }

    @Test
    fun `entries are isolated to a single coroutine context`() = runTest {
        withAnalyticsContext("jobId" to "outer") {
            withContext(kotlin.coroutines.EmptyCoroutineContext) {
                // Still inside the outer scope; entries propagate.
                assertEquals(mapOf("jobId" to "outer"), analyticsContext().toMap())
            }
        }
        // After all scopes return, entries are gone.
        assertEquals(null, kotlinx.coroutines.currentCoroutineContext()[AnalyticsContextElement.Key])
    }

    @Test
    fun `mergedWith composes maps with new keys winning`() {
        val element = AnalyticsContextElement(mapOf("a" to 1, "b" to 2))
        val merged = element.mergedWith(mapOf("b" to 3, "c" to 4))
        assertEquals(mapOf("a" to 1, "b" to 3, "c" to 4), merged.entries.toMap())
    }

    @Test
    fun `withAnalyticsContext with no entries still creates a scope`() = runTest {
        val seen = withAnalyticsContext { analyticsContext().toMap() }
        assertEquals(emptyMap(), seen)
    }

    @Test
    fun `scope lookup returns attached entries or an empty map`() {
        assertEquals(emptyMap(), AnalyticsContextElement.get(CoroutineScope(EmptyCoroutineContext)).toMap())
        assertEquals(
            mapOf("job" to "job-1"),
            AnalyticsContextElement.get(CoroutineScope(AnalyticsContextElement(mapOf("job" to "job-1")))).toMap(),
        )
    }
}
