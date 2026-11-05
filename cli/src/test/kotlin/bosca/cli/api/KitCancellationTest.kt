package bosca.cli.api

import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.*
import kotlin.test.*

class KitCancellationTest {
    @Test
    fun `cancel before submission performs no API work`() = runBlocking {
        KitHarnessFixture().use { fixture ->
            val control = KitTurnControl().apply { requestCancellation() }
            assertFailsWith<KitTurnCancelledBeforeSubmission> {
                withContext(control) { fixture.api().ask("Work") }
            }
            assertTrue(fixture.operations.isEmpty())
        }
    }

    @Test
    fun `cancellation arriving during submission is forwarded once and waits for confirmation`() = runBlocking {
        KitHarnessFixture("CANCELLED", waitForCancellation = true, submissionGate = CountDownLatch(1)).use { fixture ->
            fixture.confirmCancellation = false
            val control = KitTurnControl()
            val work = async(control) { fixture.api().ask("Work", timeoutMillis = 5_000) }
            awaitOperation(fixture, "SendKitChatMessage")
            control.requestCancellation()
            control.requestCancellation()
            fixture.submissionGate.countDown()
            awaitOperation(fixture, "CancelKitChatSession")
            delay(50)
            assertFalse(work.isCompleted, "An accepted cancellation is not a terminal response")
            fixture.confirmCancellation = true
            assertTrue(work.await().responseContent().cancelled)
            assertEquals(1, fixture.operations.count { it == "SendKitChatMessage" })
            assertEquals(1, fixture.operations.count { it == "CancelKitChatSession" })
        }
    }

    @Test
    fun `rejected and unconfirmed cancellations fail without replay`() = runBlocking {
        for (accepted in listOf(false, true)) {
            KitHarnessFixture("CANCELLED", waitForCancellation = true, acceptCancellation = accepted).use { fixture ->
                fixture.confirmCancellation = false
                val control = KitTurnControl()
                val cancellation = launch { awaitOperation(fixture, "SendKitChatMessage"); control.requestCancellation() }
                val error = assertFailsWith<IllegalStateException> {
                    withContext(control) { fixture.api().ask("Work", timeoutMillis = 200) }
                }
                assertContains(error.message.orEmpty(), if (accepted) "outcome is unconfirmed" else "did not accept cancellation")
                cancellation.join()
                assertEquals(1, fixture.operations.count { it == "SendKitChatMessage" })
                assertEquals(1, fixture.operations.count { it == "CancelKitChatSession" })
            }
        }
    }

    @Test
    fun `confirmed completion and interruption win over a cancellation request`() = runBlocking {
        for (outcome in listOf("COMPLETED", "INTERRUPTED")) {
            KitHarnessFixture(outcome, waitForCancellation = true).use { fixture ->
                val control = KitTurnControl()
                val work = async(control) { fixture.api().ask("Work", timeoutMillis = 5_000) }
                awaitOperation(fixture, "SendKitChatMessage")
                control.requestCancellation()
                val response = work.await().responseContent()
                assertEquals(outcome, response.status)
                assertFalse(response.cancelled)
                assertEquals(outcome == "INTERRUPTED", response.failed)
            }
        }
    }

    @Test
    fun `parent coroutine cancellation does not claim or request durable cancellation`() = runBlocking {
        KitHarnessFixture(waitForCancellation = true).use { fixture ->
            val work = async(KitTurnControl()) { fixture.api().ask("Work") }
            awaitOperation(fixture, "SendKitChatMessage")
            work.cancelAndJoin()
            assertTrue(work.isCancelled)
            assertTrue(fixture.operations.none { it == "CancelKitChatSession" })
        }
    }

    private suspend fun awaitOperation(fixture: KitHarnessFixture, operation: String) = withTimeout(5_000) {
        while (operation !in fixture.operations) delay(5)
    }
}
