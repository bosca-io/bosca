package bosca.ai.kit.harness.runtime

import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.chat.model.ChatMessagePartInput
import bosca.ai.kit.harness.*
import bosca.db.connection
import bosca.security.encryption.EncryptionService
import bosca.security.model.*
import bosca.security.service.ApiTokenCreationResult
import bosca.serialization.*
import bosca.storage.service.ObjectStorageService
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Runs two installed Kotlin supervisors and native fixtures, with real PostgreSQL and Koog. */
class KitDelegationRuntimeIntegrationTest {
    @Test fun `parent detaches without replay while a separate Claude child returns and archives`(): Unit = runBlocking {
        withTimeout(90_000) {
            val cli = Path.of(System.getenv("KIT_RUNTIME_CLI") ?: "../../cli/build/install/bosca/bin/bosca").toAbsolutePath().normalize()
            val node = System.getenv("PATH").split(java.io.File.pathSeparator).map { Path.of(it, "node") }.first(Files::isExecutable).toString()
            val root = Files.createTempDirectory("kit-delegation-runtime-")
            val processes = ConcurrentHashMap<UUID, Process>()
            val roots = ConcurrentHashMap<UUID, Path>()
            val workloads = ConcurrentHashMap<UUID, KitRuntimeExecution>()
            val archives = ConcurrentHashMap<String, ByteArray>()
            val credentials = AtomicLong(1000)
            val version = ProcessBuilder(cli.toString(), "--version").start().let { process ->
                val value = process.inputStream.bufferedReader().readText().trim().substringAfterLast(' ')
                check(process.waitFor() == 0); value
            }
            fun descriptor(family: String) = Path.of(if (family == "claude") "runtime/claude/compatibility.json" else "runtime/compatibility.json")
            fun versions(family: String): RuntimeVersions {
                val data = Json.parseToJsonElement(Files.readString(descriptor(family))).jsonObject
                return RuntimeVersions(version, data.getValue("adapter").jsonPrimitive.content.substringAfterLast('@'),
                    data.getValue("harness").jsonPrimitive.content.substringAfterLast('@'), data.getValue("statePolicy").jsonPrimitive.content)
            }
            try {
                KitDelegationFixture().use { f ->
                    f.profile = f.profile.copy(versions = versions("codex"), authenticationMethod = null)
                    f.childProfile = f.profile.copy(profile = "claude", versions = versions("claude"))
                    f.initialize()
                    coEvery { f.tokens.createEphemeralToken(f.principalId, any(), f.principalId, true) } answers {
                        val id = credentials.incrementAndGet()
                        ApiTokenCreationResult(PrincipalCredential(id, f.principalId, CredentialType.API_TOKEN, JsonObject(emptyMap())), "fixture-scoped-$id")
                    }
                    coEvery { f.provider.ensure(any(), any(), any(), any()) } coAnswers {
                        val config = firstArg<KitHarnessConfiguration>()
                        val environment = thirdArg<Map<String, String>>()
                        val sessionId = UUID.parse(environment.getValue("BOSCA_CHAT_SESSION_ID"))
                        val directory = Files.createDirectories(root.resolve(sessionId.toString()))
                        roots[sessionId] = directory
                        val family = if (config.profile == "claude") "claude" else "codex"
                        val spec = Json.parseToJsonElement(Files.readString(descriptor(family))).jsonObject
                        Files.copy(descriptor(family), directory.resolve("compatibility.json"))
                        for (reference in listOf("adapter", "harness").map { spec.getValue(it).jsonPrimitive.content }) {
                            val path = directory.resolve("node_modules/${reference.substringBeforeLast('@')}/package.json")
                            Files.createDirectories(path.parent)
                            Files.writeString(path, """{"name":"${reference.substringBeforeLast('@')}","version":"${reference.substringAfterLast('@')}"}""")
                        }
                        val secret = arg<KitRuntimeSecrets>(3)
                        val plain = f.json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), Base64.getDecoder().decode(secret.ciphertext).decodeToString())
                        val port = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
                        val process = ProcessBuilder(cli.toString(), "harness", "serve", "--host", "127.0.0.1", "--port", port.toString(), "--root", directory.toString())
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(directory.resolve("runtime.log").toFile()).apply {
                                environment().putAll(environment + plain + mapOf(
                                    "BOSCA_RUNTIME_HOME" to directory.toString(), "BOSCA_NO_UPDATE_CHECK" to "1",
                                    "BOSCA_ACP_COMMAND" to JsonArray(listOf(node, Path.of("runtime/tests/fake_adapter.mjs").toAbsolutePath().toString()).map(::JsonPrimitive)).toString(),
                                    "TEST_HARNESS_HOME" to directory.resolve("harness").toString(),
                                    "TEST_HARNESS_STATE_PATH" to if (family == "claude") "projects/fixture/test.jsonl" else "sessions/test.json"))
                            }.start()
                        processes[sessionId] = process
                        val endpoint = "http://127.0.0.1:$port"
                        withTimeout(15_000) { while (true) {
                            check(process.isAlive) { Files.readString(directory.resolve("runtime.log")) }
                            val ready = runInterruptible(Dispatchers.IO) {
                                try {
                                    java.net.URI("$endpoint/healthz").toURL().openConnection().apply {
                                        connectTimeout = 500
                                        readTimeout = 500
                                    }.getInputStream().use { true }
                                } catch (_: java.io.IOException) { false }
                            }
                            if (ready) break
                            delay(25)
                        } }
                        UUID.random().also { workloads[it] = KitRuntimeExecution(OffsetDateTime.now(), false, endpoint, config.image, config.imageId) }
                    }
                    coEvery { f.provider.status(any(), any()) } answers { workloads[secondArg()] }
                    coEvery { f.provider.isStopped(any(), any()) } answers { workloads[secondArg()]?.terminal == true }
                    coEvery { f.provider.stop(any(), any()) } coAnswers {
                        val reference = secondArg<UUID>()
                        val session = roots.keys.mapNotNull { f.runtimes.get(it) }.firstOrNull { it.dispatchId == reference }
                        session?.let { processes.remove(it.sessionId)?.let { process ->
                            process.destroy(); withContext(Dispatchers.IO) { if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly().waitFor() }
                        } }
                        workloads[reference]?.let { workloads[reference] = it.copy(terminal = true) }
                        Unit
                    }
                    val storage = mockk<ObjectStorageService> {
                        coEvery { setInputStream(any(), any(), any()) } answers {
                            val bytes = secondArg<java.io.InputStream>().readAllBytes(); archives[firstArg<Any>().toString()] = bytes; bytes.size.toLong()
                        }
                        coEvery { delete(any()) } answers { archives.remove(firstArg<Any>().toString()); Unit }
                        coEvery { getInputStream(any()) } answers { archives.getValue(firstArg<Any>().toString()).inputStream() }
                    }
                    val driver = KitRuntimeDriver(f.runtimes, f.provider, f.tokens, f.encryption, storage, f.history, f.sessions, mockk(), f.json, f.policy, f.context, f.telemetry, f.service)
                    suspend fun drive(id: UUID) = f.request { driver.reconcile(id) }
                    suspend fun waiting(id: UUID) { withTimeout(20_000) { while (roots[id]?.resolve("harness/waiting-pid")?.let(Files::exists) != true) delay(25) } }
                    f.request {
                        f.runtimes.finish(f.turn.id, "COMPLETED", null)
                        val current = assertNotNull(f.runtimes.get(f.sessionId))
                        f.runtimes.save(current.copy(state = "STOPPED", activeTurn = null, credentialId = null, secrets = null))
                        f.harness.send(f.auth(), f.sessionId, ChatMessageInput("user", listOf(ChatMessagePartInput("text", "delegate-wait"))))
                    }
                    val parentWork = async { try { drive(f.sessionId); null } catch (waiting: KitDelegationWaitException) { waiting } }
                    waiting(f.sessionId)
                    val child = f.request {
                        val parent = assertNotNull(f.runtimes.get(f.sessionId))
                        val authentication = f.auth(assertNotNull(parent.credentialId))
                        f.service.start(authentication, f.sessionId, f.input(60).copy(brief = "fixture-child-wait", artifacts = listOf(f.source.id))).also {
                            f.service.wait(authentication, f.sessionId, it.id)
                        }
                    }
                    assertIs<KitDelegationWaitException>(parentWork.await())
                    f.request {
                        val parent = assertNotNull(f.runtimes.get(f.sessionId))
                        assertNull(parent.owner); assertNull(parent.leaseUntil)
                        assertEquals("SENT", f.runtimes.turn(assertNotNull(parent.activeTurn))?.status)
                    }
                    assertTrue(processes.getValue(f.sessionId).isAlive)
                    assertFalse(Files.exists(roots.getValue(f.sessionId).resolve("harness/cancel-observed")))
                    assertFalse(Files.exists(roots.getValue(f.sessionId).resolve("harness/rpc-cancel-observed")))
                    // All four pooled connections can be borrowed while the native parent keeps waiting.
                    coroutineScope {
                        val acquired = AtomicInteger(); val ready = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                        val borrowers = (1..4).map { async(Dispatchers.IO) { f.request {
                            connection().useStatement("select 1") { it.execute() }
                            if (acquired.incrementAndGet() == 4) ready.complete(Unit)
                            release.await()
                        } } }
                        try { withTimeout(5000) { ready.await() } } finally { release.complete(Unit) }
                        borrowers.awaitAll()
                    }
                    val childWork = async { drive(child.childSessionId) }
                    waiting(child.childSessionId)
                    assertNotEquals(roots.getValue(f.sessionId), roots.getValue(child.childSessionId))
                    f.request {
                        val runtime = assertNotNull(f.runtimes.get(child.childSessionId))
                        f.service.submit(f.auth(assertNotNull(runtime.credentialId)), child.childSessionId, KitDelegationResult("Verified child answer", listOf(KitDelegationArtifact(f.source.id, "Plan"))))
                    }
                    Files.writeString(roots.getValue(child.childSessionId).resolve("release-delegation"), "release")
                    childWork.await()
                    f.request { assertEquals(KitDelegationStatus.COMPLETED, f.repository.get(child.id)?.status) }
                    assertEquals("1", Files.readString(roots.getValue(child.childSessionId).resolve("harness/prompt-count")))
                    assertFalse(Files.readString(roots.getValue(child.childSessionId).resolve("harness/last-prompt.json")).contains("Parent private history"))
                    Files.writeString(roots.getValue(f.sessionId).resolve("release-delegation"), "release")
                    withTimeout(5000) { while (true) {
                        try { drive(f.sessionId); break } catch (_: bosca.sharedqueue.jobs.DelayException) { delay(25) }
                    } }
                    f.request {
                        assertNull(f.runtimes.get(f.sessionId)?.activeTurn)
                        assertEquals(1, f.history.getMessages(f.sessionId).count { it.event.toString().contains("Verified child answer") })
                    }
                    assertEquals("1", Files.readString(roots.getValue(f.sessionId).resolve("harness/prompt-count")))
                    drive(child.childSessionId)
                    f.request { assertNotNull(f.runtimes.get(child.childSessionId)?.archiveKey) }
                    assertTrue(archives.isNotEmpty())

                    // A later parent turn can delegate again; cancelling it reaches an active child.
                    Files.delete(roots.getValue(f.sessionId).resolve("release-delegation"))
                    Files.delete(roots.getValue(f.sessionId).resolve("harness/waiting-pid"))
                    f.request {
                        val parent = assertNotNull(f.runtimes.get(f.sessionId))
                        f.harness.send(f.auth(assertNotNull(parent.credentialId)), f.sessionId, ChatMessageInput("user", listOf(ChatMessagePartInput("text", "delegate-wait"))))
                    }
                    val secondParent = async { try { drive(f.sessionId); null } catch (waiting: KitDelegationWaitException) { waiting } }
                    waiting(f.sessionId)
                    val cancelledChild = f.request {
                        val parent = assertNotNull(f.runtimes.get(f.sessionId))
                        val authentication = f.auth(assertNotNull(parent.credentialId))
                        f.service.start(authentication, f.sessionId, f.input(60).copy(brief = "fixture-child-wait")).also { f.service.wait(authentication, f.sessionId, it.id) }
                    }
                    assertIs<KitDelegationWaitException>(secondParent.await())
                    val cancelledWork = async { drive(cancelledChild.childSessionId) }
                    waiting(cancelledChild.childSessionId)
                    f.request {
                        val runtime = assertNotNull(f.runtimes.get(cancelledChild.childSessionId))
                        f.service.submit(f.auth(assertNotNull(runtime.credentialId)), cancelledChild.childSessionId, KitDelegationResult("Partial work before cancellation"))
                        f.harness.cancel(f.sessionId)
                    }
                    cancelledWork.await()
                    withTimeout(5000) { while (true) {
                        try { drive(f.sessionId); break } catch (_: bosca.sharedqueue.jobs.DelayException) { delay(25) }
                    } }
                    f.request {
                        assertEquals(KitDelegationStatus.CANCELLED, f.repository.get(cancelledChild.id)?.status)
                        assertEquals("Partial work before cancellation", f.repository.get(cancelledChild.id)?.result?.summary)
                        assertNull(f.runtimes.get(f.sessionId)?.activeTurn)
                    }
                    assertTrue(Files.exists(roots.getValue(f.sessionId).resolve("harness/cancel-observed")))
                    assertTrue(Files.exists(roots.getValue(cancelledChild.childSessionId).resolve("harness/cancel-observed")))
                    assertEquals("2", Files.readString(roots.getValue(f.sessionId).resolve("harness/prompt-count")))
                    f.request { f.harness.delete(f.sessionId) }
                    assertFailsWith<bosca.sharedqueue.jobs.DelayException> { drive(f.sessionId) }
                    drive(child.childSessionId); drive(cancelledChild.childSessionId); drive(f.sessionId)
                    f.request { assertNull(f.runtimes.get(f.sessionId)); assertNull(f.runtimes.get(child.childSessionId)) }
                    assertTrue(archives.isEmpty())
                }
            } finally {
                processes.values.forEach { it.destroy(); if (!it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly().waitFor() }
                root.toFile().deleteRecursively()
            }
        }
    }
}
