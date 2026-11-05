package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.*
import bosca.ai.kit.harness.acp.AcpConnectionException
import bosca.ai.kit.harness.acp.KitAcpConnection
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.encodeToJsonElement
import java.io.EOFException
import java.time.Instant
import java.util.Base64
import kotlin.test.*

class KitRuntimeStartupDeadlineIntegrationTest {
    @Test fun `ACP closure at the hard startup deadline cleans up without sending queued work`(): Unit = runBlocking {
        withStartingRuntime(4) { f, driver, queued, deadline ->
            coEvery { KitAcpConnection.connect(any(), any(), any(), any()) } coAnswers {
                // A transport callback can win the race with the runner's timeout cancellation.
                withContext(NonCancellable) {
                    delay((deadline.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0) + 50)
                    throw AcpConnectionException(EOFException("Runtime expired"))
                }
            }
            f.request { driver.reconcile(f.sessionId) }
            coVerify(exactly = 1) { KitAcpConnection.connect(any(), any(), any(), any()) }
            f.request {
                val failed = assertNotNull(f.runtimes.get(f.sessionId))
                assertEquals("FAILED", failed.state)
                assertContains(assertNotNull(failed.failure), "hard-runtime deadline during startup")
                assertNull(failed.dispatchId)
                assertNull(failed.credentialId)
                assertNull(failed.secrets)
                assertEquals("QUEUED", f.runtimes.turn(queued.id)?.status)
                assertNull(f.runtimes.turn(queued.id)?.deadline)
            }
            coVerify(exactly = 1) { f.provider.stop(any(), any()) }
            coVerify(exactly = 1) { f.tokens.revokeToken(101, f.principalId) }
            coVerify(exactly = 1) { f.tokens.deleteToken(101, f.principalId) }
        }
    }

    @Test fun `ACP failure before the startup deadline still propagates`(): Unit = runBlocking {
        withStartingRuntime(30) { f, driver, queued, _ ->
            val failure = AcpConnectionException(EOFException("Unexpected closure"))
            coEvery { KitAcpConnection.connect(any(), any(), any(), any()) } throws failure
            val thrown = assertFailsWith<AcpConnectionException> { f.request { driver.reconcile(f.sessionId) } }
            assertEquals(failure.message, thrown.message)
            assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it is EOFException && it.message == "Unexpected closure" })
            f.request {
                assertEquals("STARTING", f.runtimes.get(f.sessionId)?.state)
                assertEquals("QUEUED", f.runtimes.turn(queued.id)?.status)
                assertNull(f.runtimes.turn(queued.id)?.deadline)
            }
            coVerify(exactly = 0) { f.provider.stop(any(), any()) }
            coVerify(exactly = 0) { f.tokens.revokeToken(any(), any()) }
        }
    }

    @Test fun `parent cancellation is preserved when ACP closes after the startup deadline`(): Unit = runBlocking {
        withStartingRuntime(4) { f, driver, _, deadline ->
            val connecting = CompletableDeferred<Unit>()
            coEvery { KitAcpConnection.connect(any(), any(), any(), any()) } coAnswers {
                connecting.complete(Unit)
                withContext(NonCancellable) {
                    delay((deadline.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0) + 50)
                    throw AcpConnectionException(EOFException("Runtime expired"))
                }
            }
            supervisorScope {
                val reconciliation = async { f.request { driver.reconcile(f.sessionId) } }
                withTimeout(15_000) { connecting.await() }
                reconciliation.cancel()
                assertFailsWith<CancellationException> { reconciliation.await() }
                reconciliation.join()
            }
            f.request { assertEquals("STARTING", f.runtimes.get(f.sessionId)?.state) }
            coVerify(exactly = 0) { f.provider.stop(any(), any()) }
            coVerify(exactly = 0) { f.tokens.revokeToken(any(), any()) }
        }
    }

    private suspend fun withStartingRuntime(
        hardSeconds: Long,
        action: suspend (KitDelegationFixture, KitRuntimeDriver, KitRuntimeTurn, Instant) -> Unit,
    ) {
        mockkConstructor(KitRuntimeClient::class)
        mockkObject(KitAcpConnection.Companion)
        try {
            KitDelegationFixture().use { f ->
                f.initialize()
                val deadline = Instant.now().plusSeconds(hardSeconds).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                val queued = f.turn.copy(id = UUID.random(), status = "QUEUED", deadline = null)
                f.request {
                    check(f.runtimes.finish(f.turn.id, "COMPLETED", null) == 1)
                    f.runtimes.add(queued)
                    val session = assertNotNull(f.runtimes.get(f.sessionId))
                    val environment = """{"BOSCA_RUNTIME_TOKEN":"test-token","BOSCA_RUNTIME_EXPIRES":"${deadline.epochSecond}"}""".encodeToByteArray()
                    val secret = KitRuntimeSecrets(f.sessionId, Base64.getEncoder().encodeToString(byteArrayOf(1)),
                        Base64.getEncoder().encodeToString(environment), listOf("BOSCA_RUNTIME_TOKEN", "BOSCA_RUNTIME_EXPIRES"))
                    check(f.runtimes.save(session.copy(state = "STARTING", activeTurn = null, dispatchId = UUID.random(),
                        secrets = f.json.encodeToJsonElement(KitRuntimeSecrets.serializer(), secret))) == 1)
                }
                coEvery { anyConstructed<KitRuntimeClient>().claim(any(), any()) } answers {
                    val session = firstArg<KitRuntimeSession>()
                    RuntimeStatus(session.sessionId.toString(), session.generation, "PREPARED", "fixture-instance", epoch = session.epoch, versions = f.profile.versions)
                }
                coEvery { anyConstructed<KitRuntimeClient>().start(any(), any()) } returns mockk(relaxed = true)
                coEvery { f.provider.status(any(), any()) } returns KitRuntimeExecution(OffsetDateTime.now(), false,
                    "http://127.0.0.1:1", f.profile.image, f.profile.imageId)
                var stopped = false
                coEvery { f.provider.isStopped(any(), any()) } answers { stopped }
                coEvery { f.provider.stop(any(), any()) } answers { stopped = true }
                coEvery { f.tokens.getTokenById(101) } returns mockk(relaxed = true)
                val driver = KitRuntimeDriver(f.runtimes, f.provider, f.tokens, f.encryption, mockk(), f.history, f.sessions,
                    mockk(), f.json, f.policy, f.context, f.telemetry, f.service)
                action(f, driver, queued, deadline)
            }
        } finally {
            unmockkObject(KitAcpConnection.Companion)
            unmockkConstructor(KitRuntimeClient::class)
        }
    }
}
