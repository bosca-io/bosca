package bosca.git.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PageLoadingTest {
    @Test
    fun `fan out is bounded preserves order and completes in concurrent batches`() = runTest {
        var active = 0
        var peak = 0
        val result = (1..10).loadConcurrently { value ->
            active++
            peak = maxOf(peak, active)
            delay(100)
            active--
            value * 2
        }
        assertEquals((1..10).map { it * 2 }, result)
        assertEquals(4, peak)
        assertEquals(300, testScheduler.currentTime)
    }

    @Test
    fun `a failed read cancels its siblings and does not start later batches`() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val visited = mutableListOf<Int>()
        assertFailsWith<IllegalStateException> {
            (1..10).loadConcurrently { value ->
                visited.add(value)
                if (value == 2) {
                    started.await()
                    error("Read failed")
                }
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally { cancelled.complete(Unit) }
            }
        }
        assertTrue(cancelled.isCompleted)
        assertTrue(visited.none { it > 4 })
    }

    @Test
    fun `request cancellation cancels in flight reads`() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val request = async {
            listOf(1).loadConcurrently {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally { cancelled.complete(Unit) }
            }
        }
        started.await()
        request.cancel()
        request.join()
        assertTrue(cancelled.isCompleted)
    }
}
