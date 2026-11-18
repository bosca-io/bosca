package bosca.cli.harness

import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.netty.NettyApplicationEngine.Configuration
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.request.path
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*
import kotlin.uuid.Uuid

class HarnessRuntimeTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var runtime: HarnessRuntime
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, Configuration>
    private val client = OkHttpClient()
    private lateinit var base: String
    private var epoch = 1L
    private val token = "a".repeat(32)
    private val secret = "fixture-secret-token"

    @BeforeTest fun setup() = runBlocking { startRuntime() }
    private suspend fun startRuntime(maxJournalBytes: Long = 64L * 1024 * 1024, maxJournalTurns: Int = 256, lifetimeSeconds: Long = 300) {
        val node = System.getenv("PATH").split(':').map { Path.of(it, "node") }.first { it.isExecutable() }
        val roots = HarnessArchive.ROOTS.associateWith { directory.root.toPath().resolve(it) }
        runtime = HarnessRuntime("fixture", 1, token, System.currentTimeMillis() / 1000 + lifetimeSeconds,
            listOf(node.toString(), Path.of("../ai/kit/runtime/tests/fake_adapter.mjs").toAbsolutePath().normalize().toString()),
            roots, directory.root.toPath().resolve("control"), bosca.ai.kit.harness.RuntimeVersions("test", "test", "test", "codex-0.160.1"),
            environment = mapOf("TEST_HARNESS_HOME" to roots.getValue("harness").toString(), "TEST_SECRET_TOKEN" to secret),
            maxJournalBytes = maxJournalBytes, maxJournalTurns = maxJournalTurns)
        server = harnessServer(runtime, "127.0.0.1", 0).start()
        base = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}"
        call("/v1/claim").use { assertEquals(200, it.code) }
        call("/v1/start").use { assertEquals(200, it.code) }
    }
    @AfterTest fun cleanup() {
        server.stop(0, 1000)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test fun `HTTP control cancellation is not converted into a runtime conflict`(): Unit = runBlocking {
        val received = CompletableDeferred<Unit>()
        server.application.receivePipeline.intercept(ApplicationReceivePipeline.Before) {
            if (call.request.path() == "/v1/mcp") {
                received.complete(Unit)
                throw CancellationException("Control request cancelled")
            }
        }
        val bounded = client.newBuilder().readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
        val status = withContext(Dispatchers.IO) {
            try {
                bounded.newCall(request("/v1/mcp").post("[]".toRequestBody()).build()).execute().use { it.code }
            } catch (_: java.io.IOException) { null }
        }
        assertTrue(received.isCompleted, "The request must reach the control handler")
        assertNotEquals(409, status, "Cancellation must escape the runtime state-error handler")
    }

    private fun request(path: String) = Request.Builder().url(base + path).header("Authorization", "Bearer $token")
        .header("X-Bosca-Generation", "1").header("X-Bosca-Epoch", epoch.toString()).header("X-Bosca-Instance", runtime.instanceId)

    @Test fun `control and ACP reject an old process identity even when generation credentials match`(): Unit = runBlocking {
        val original = runtime.instanceId
        for (path in listOf("/v1/claim", "/v1/start", "/v1/archive", "/v1/cancel", "/v1/restore", "/v1/mcp", "/v1/permission")) {
            call(path, headers = mapOf("X-Bosca-Instance" to Uuid.random().toString())).use {
                assertEquals(409, it.code)
                assertEquals(original, it.header("X-Bosca-Instance"))
            }
        }
        val rejected = CompletableDeferred<Int>()
        val socket = client.newWebSocket(request("/acp").header("X-Bosca-Instance", Uuid.random().toString()).build(), object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { rejected.complete(response?.code ?: -1) }
        })
        try { assertEquals(409, withTimeout(5000) { rejected.await() }) } finally { socket.cancel() }
        client.newCall(request("/v1/runtime").removeHeader("X-Bosca-Instance").build()).execute().use { assertEquals(409, it.code) }
        client.newCall(request("/v1/claim").removeHeader("X-Bosca-Instance").post("".toRequestBody()).build()).execute().use { assertEquals(200, it.code) }
        runtime.claim(++epoch)
        assertEquals(original, runtime.status().instanceId, "Runner ownership changes retain process identity")
        server.stop(0, 1000)
        startRuntime()
        assertNotEquals(original, runtime.status().instanceId, "Restarting the supervisor creates a fresh process identity")
    }
    private fun call(path: String, body: String = "", headers: Map<String, String> = emptyMap()): Response = client.newCall(
        request(path).apply { headers.forEach { (key, value) -> header(key, value) } }.post(body.toRequestBody()).build()).execute()
    private suspend fun socket(action: suspend (WebSocket, Channel<JsonObject>) -> Unit) {
        val messages = Channel<JsonObject>(Channel.UNLIMITED)
        val opened = CompletableDeferred<Unit>()
        val socket = client.newWebSocket(request("/acp").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) { messages.trySend(Json.parseToJsonElement(text).jsonObject) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { opened.completeExceptionally(t); messages.close(t) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        })
        try { withTimeout(10_000) { opened.await(); action(socket, messages) } }
        finally { socket.close(1000, "test finished"); runtime.claim(++epoch) }
    }
    private fun prompt(turn: String, text: String) = buildJsonObject {
        put("sessionId", "native-test"); putJsonArray("prompt") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
        putJsonObject("_meta") { put("turnId", turn); put("generation", 1) }
    }
    private fun send(socket: WebSocket, method: String, params: JsonObject, id: Int = 1) {
        assertTrue(socket.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }.toString()))
    }
    private suspend fun response(messages: Channel<JsonObject>, id: Int = 1): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        do { result += messages.receive() } while (result.last()["id"] != JsonPrimitive(id))
        return result
    }

    @Test fun `hard deadline stops a disconnected native process and retains its uncertain receipt`(): Unit = runBlocking {
        server.stop(0, 1000)
        startRuntime(lifetimeSeconds = 3)
        val turn = Uuid.random().toString()
        val pidFile = runtime.roots.getValue("harness").resolve("waiting-pid")
        socket { socket, _ ->
            send(socket, "session/prompt", prompt(turn, "ignore-cancel"))
            withTimeout(2000) { while (!pidFile.exists()) delay(10) }
        }
        val pid = pidFile.readText().toLong()
        withTimeout(5000) { while (runtime.status().failure == null || ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10) }
        assertContains(assertNotNull(runtime.status().failure), "hard-runtime deadline")
        assertFalse(assertNotNull(runtime.turn(turn)).complete)
        call("/v1/claim").use { assertEquals(401, it.code) }
        assertFailsWith<IllegalStateException> { runtime.start(epoch) }
        assertFailsWith<IllegalStateException> { runtime.archive(epoch) }
    }

    @Test fun `observer RPC cancellation leaves the durable prompt alive until fenced cancellation`(): Unit = runBlocking {
        val turn = Uuid.random().toString()
        socket { socket, messages ->
            send(socket, "session/prompt", prompt(turn, "delegate-wait"))
            val harness = runtime.roots.getValue("harness")
            withTimeout(2000) { while (!harness.resolve("waiting-pid").exists()) delay(10) }
            assertTrue(socket.send(buildJsonObject {
                put("jsonrpc", "2.0"); put("method", "\$/cancelRequest")
                putJsonObject("params") { put("requestId", 1); put("message", "Observer detached for child wait") }
            }.toString()))
            delay(150)
            assertFalse(harness.resolve("rpc-cancel-observed").exists(), "Observer cancellation must not reach the native request")
            assertFalse(assertNotNull(runtime.turn(turn)).complete)
            runtime.cancel(turn, epoch)
            val terminal = response(messages).last()
            assertEquals("cancelled", terminal.getValue("result").jsonObject.getValue("stopReason").jsonPrimitive.content)
            assertTrue(harness.resolve("cancel-observed").exists())
        }
    }

    @Test fun `blocked native input cannot hold the protocol mutex past the hard deadline`(): Unit = runBlocking {
        server.stop(0, 1000)
        directory.root.toPath().resolve("hold-initialize").writeText("")
        startRuntime(lifetimeSeconds = 3)
        runtime.receive(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "initialize"); putJsonObject("params") { }
        }, epoch)
        withTimeout(2000) {
            while (!runtime.roots.getValue("harness").resolve("initialization-started").exists()) delay(10)
        }
        withTimeout(5000) {
            assertFailsWith<java.io.IOException> {
                runtime.receive(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", 2); put("method", "session/prompt")
                    put("params", prompt(Uuid.random().toString(), "x".repeat(1024 * 1024)))
                }, epoch)
            }
            while (runtime.status().failure == null) delay(10)
        }
        assertEquals("FAILED", runtime.status().phase)
        assertContains(assertNotNull(runtime.status().failure), "hard-runtime deadline")
    }

    @Test fun `shutdown stops a native process whose input is blocked without waiting for its deadline`(): Unit = runBlocking {
        blockedInputShutdown(false)
    }

    @Test fun `shutdown releases an archive waiting behind a blocked native write`(): Unit = runBlocking {
        blockedInputShutdown(true)
    }

    @Test fun `hard deadline releases an archive waiting behind a blocked native write`(): Unit = runBlocking {
        blockedInputShutdown(true, expire = true)
    }

    private suspend fun blockedInputShutdown(archivePending: Boolean, expire: Boolean = false): Unit = coroutineScope {
        server.stop(0, 1000)
        directory.root.toPath().resolve("hold-initialize").writeText("")
        startRuntime(lifetimeSeconds = if (expire) 3 else 300)
        runtime.receive(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "initialize"); putJsonObject("params") { }
        }, epoch)
        val harness = runtime.roots.getValue("harness")
        withTimeout(2000) { while (!harness.resolve("initialization-started").exists()) delay(10) }
        val pid = harness.resolve("process-pid").readText().toLong()
        val started = CompletableDeferred<Unit>()
        val sending = async(Dispatchers.IO) {
            started.complete(Unit)
            try {
                runtime.receive(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", 2); put("method", "session/prompt")
                    put("params", prompt(Uuid.random().toString(), "x".repeat(1024 * 1024)))
                }, epoch)
                null
            } catch (failure: java.io.IOException) { failure }
        }
        started.await()
        delay(100)
        assertFalse(sending.isCompleted, "The fixture must hold a native pipe write")
        val archiveStarted = CompletableDeferred<Unit>()
        val archiving = if (archivePending) async(Dispatchers.Default) {
            archiveStarted.complete(Unit)
            try { runtime.archive(epoch); null } catch (failure: IllegalStateException) { failure }
        } else null
        if (archiving != null) {
            archiveStarted.await()
            delay(100)
            assertFalse(archiving.isCompleted)
        }
        val closing = async(Dispatchers.Default) {
            if (expire) {
                while (runtime.status().failure?.contains("hard-runtime deadline") != true) delay(10)
            } else runtime.close()
        }
        try {
            assertEquals(true, withTimeoutOrNull(if (expire) 5000L else 2000L) { closing.await(); true },
                "Termination waited for a runtime lock instead of stopping native I/O")
            assertNotNull(sending.await())
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        } finally {
            ProcessHandle.of(pid).ifPresent { if (it.isAlive) it.destroyForcibly() }
            withContext(NonCancellable) { sending.join(); archiving?.join(); closing.join() }
        }
    }

    @Test fun `journal capacity preserves receipts and rejects new prompts until archival`() = runBlocking {
        for (limit in listOf("bytes", "turns")) {
            server.stop(0, 1000)
            runtime.control.toFile().deleteRecursively()
            startRuntime(maxJournalBytes = if (limit == "bytes") 1024 else 4096, maxJournalTurns = if (limit == "turns") 1 else 256)
            val turn = Uuid.random().toString()
            val text = if (limit == "bytes") "x".repeat(600) else "one-turn"
            socket { socket, messages ->
                send(socket, "session/prompt", prompt(turn, text))
                assertTrue("result" in response(messages).last())
            }
            assertTrue(runtime.status().retireRequested)
            val receipt = assertNotNull(runtime.turn(turn))
            assertTrue(receipt.complete)
            socket { socket, messages ->
                val rejected = Uuid.random().toString()
                send(socket, "session/prompt", prompt(rejected, "must-not-run"))
                assertContains(response(messages).last().toString(), "requires archival")
                assertNull(runtime.turn(rejected))
                assertFalse(runtime.control.resolve("$rejected.jsonl").exists())
                send(socket, "session/prompt", prompt(turn, "duplicate"))
                assertContains(response(messages).last().toString(), "already submitted")
            }
            assertEquals(receipt, runtime.turn(turn), "New ownership retains the complete receipt")
            assertEquals(text, runtime.roots.getValue("harness").resolve("sessions/test.json").readText())
            runtime.archive(epoch)
            assertEquals("ARCHIVED", runtime.status().phase)
        }
    }

    @Test fun `journal overflow stops native output without completing or replaying the accepted turn`() = runBlocking {
        for (text in listOf("journal-flood", "oversized-line")) {
            server.stop(0, 1000)
            runtime.control.toFile().deleteRecursively()
            startRuntime(maxJournalBytes = 1024)
            val turn = Uuid.random().toString()
            socket { socket, _ ->
                send(socket, "session/prompt", prompt(turn, text))
                withTimeout(5000) { while (runtime.status().phase != "FAILED") delay(10) }
            }
            assertContains(assertNotNull(runtime.status().failure), "journal exceeded")
            val receipt = assertNotNull(runtime.turn(turn))
            assertFalse(receipt.complete)
            assertEquals(if (text == "journal-flood") 1 else 0, receipt.messages.size)
            assertTrue(runtime.control.resolve("$turn.jsonl").fileSize() <= 1024)
            val pid = runtime.roots.getValue("harness").resolve("journal-writer-pid").readText().toLong()
            withTimeout(5000) { while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10) }
            assertFailsWith<IllegalStateException> { runtime.archive(epoch) }
        }
    }

    @Test fun `failed journal writes retain their budget and never forward prompts`() = runBlocking {
        server.stop(0, 1000)
        startRuntime(maxJournalBytes = 1024)
        var attempts = 0
        while (!runtime.status().retireRequested) {
            assertTrue(attempts++ < 20, "Failed writes must not bypass the generation budget")
            val turn = Uuid.random().toString()
            runtime.control.resolve("$turn.jsonl").createDirectory()
            assertFailsWith<java.io.IOException> {
                runtime.receive(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", attempts); put("method", "session/prompt")
                    put("params", prompt(turn, "must-not-run"))
                }, epoch)
            }
            assertNull(runtime.turn(turn))
        }
        assertNull(runtime.status().activeTurn)
        assertFalse(runtime.roots.getValue("harness").resolve("sessions/test.json").exists())
        runtime.archive(epoch)
        assertEquals("ARCHIVED", runtime.status().phase)
    }

    /** An observer whose sends never finish; once [tornDown], they report cancellation as a closed Ktor session does. */
    private class ObserverSession(tornDown: Boolean) : WebSocketSession {
        override var masking = false
        override var maxFrameSize = 0L
        override val incoming = Channel<Frame>()
        override val outgoing = Channel<Frame>().apply { if (tornDown) cancel() }
        override val extensions = emptyList<WebSocketExtension<*>>()
        override val coroutineContext = EmptyCoroutineContext
        override suspend fun flush() {}
        @Deprecated("Unused by the runtime")
        override fun terminate() {}
    }

    private fun holdRichResults() = directory.root.toPath().resolve("release-rich-results")
    private suspend fun awaitEmitted() {
        val emitted = runtime.roots.getValue("harness").resolve("rich-results-emitted")
        withTimeout(5000) { while (!emitted.exists()) delay(1) }
        emitted.deleteIfExists()
    }
    private suspend fun assertEndTurn(turn: String) {
        withTimeout(5000) { while (runtime.turn(turn)?.complete != true) delay(1) }
        assertEquals("end_turn", assertNotNull(runtime.turn(turn)).messages.last().getValue("result").jsonObject.string("stopReason"))
        assertEquals("READY", runtime.status().phase)
    }

    @Test fun `a torn down observer cannot stop the native output journal`() = runBlocking {
        socket { socket, messages ->
            send(socket, "initialize", buildJsonObject {}); response(messages)
            send(socket, "session/new", buildJsonObject {}); response(messages)
            val turn = Uuid.random().toString()
            send(socket, "session/prompt", prompt(turn, "rich-results-recovery"))
            awaitEmitted()
            runtime.connect(ObserverSession(tornDown = true), epoch)
            holdRichResults().writeText("")
            assertEndTurn(turn)
        }
    }

    @Test fun `shutdown cancels native output delivery blocked on its observer`() = runBlocking {
        socket { socket, messages ->
            send(socket, "initialize", buildJsonObject {}); response(messages)
            send(socket, "session/new", buildJsonObject {}); response(messages)
            val turn = Uuid.random().toString()
            send(socket, "session/prompt", prompt(turn, "rich-results-recovery"))
            awaitEmitted()
            runtime.connect(ObserverSession(tornDown = false), epoch)
            holdRichResults().writeText("")
            // A message is journaled and then delivered under one lock, so this one is now blocked on the observer.
            val journal = runtime.control.resolve("$turn.jsonl")
            withTimeout(5000) { while (!journal.readText().contains("agent_message_chunk")) delay(5) }
            assertNull(withTimeoutOrNull(500) { runtime.status() }, "Delivery should hold the protocol mutex while its observer is blocked")
            withTimeout(10_000) { runtime.close() }
            assertEquals("DRAINING", runtime.status().phase)
        }
    }

    @Test fun `dropping the observer while native output arrives preserves the durable turn`() = runBlocking {
        socket { socket, messages ->
            send(socket, "initialize", buildJsonObject {}); response(messages)
            send(socket, "session/new", buildJsonObject {}); response(messages)
        }
        repeat(150) { attempt ->
            socket { socket, messages ->
                send(socket, "initialize", buildJsonObject {}); response(messages)
                send(socket, "session/load", buildJsonObject { put("sessionId", "native-test") }); response(messages)
                val turn = Uuid.random().toString()
                holdRichResults().deleteIfExists()
                send(socket, "session/prompt", prompt(turn, "rich-results-recovery"))
                awaitEmitted()
                // The native fixture polls every 10ms, so stepping the gap sweeps the teardown window.
                val gap = (attempt % 15).toLong()
                if (attempt % 2 == 0) { socket.cancel(); delay(gap); holdRichResults().writeText("") }
                else { holdRichResults().writeText(""); delay(gap); socket.cancel() }
                assertEndTurn(turn)
            }
        }
    }

    @Test fun `warm reconnect reuses initialization and session while settings stay cached`() = runBlocking {
        socket { socket, messages ->
            send(socket, "initialize", buildJsonObject {}); response(messages)
            send(socket, "session/new", buildJsonObject {}); response(messages)
            val setup = Json.parseToJsonElement(runtime.roots.getValue("harness").resolve("last-setup.json").readText()).jsonObject
            assertEquals(runtime.roots.getValue("workspace").toString(), setup.getValue("cwd").jsonPrimitive.content)
            send(socket, "session/set_config_option", buildJsonObject { put("sessionId", "native-test"); put("configId", "model"); put("value", "large") }); response(messages)
        }
        socket { socket, messages ->
            send(socket, "initialize", buildJsonObject {}); assertTrue("result" in response(messages).last())
            send(socket, "session/load", buildJsonObject { put("sessionId", "native-test") })
            assertContains(response(messages).last().toString(), "large")
            val turn = Uuid.random().toString()
            send(socket, "session/prompt", prompt(turn, "slow"), 20)
            withTimeout(5000) { while (runtime.status().activeTurn == null) delay(5) }
            send(socket, "session/set_config_option", buildJsonObject { put("sessionId", "native-test"); put("configId", "model"); put("value", "small") }, 21)
            assertTrue("error" in response(messages, 21).last())
            call("/v1/archive").use { assertEquals(409, it.code) }
            response(messages, 20)
            send(socket, "session/prompt", prompt(turn, "duplicate"))
            assertTrue("error" in response(messages).last())
            assertTrue(assertNotNull(runtime.turn(turn)).complete)
        }
        assertEquals("slow", runtime.roots.getValue("harness").resolve("sessions/test.json").readText())
        val receipt = runtime.archive(epoch)
        assertEquals(receipt, runtime.archive(epoch))
        assertEquals("ARCHIVED", runtime.status().phase)
    }

    @Test fun `reconnecting during native setup does not initialize the adapter twice`() = runBlocking {
        val hold = directory.root.toPath().resolve("hold-initialize")
        hold.writeText("")
        socket { socket, _ ->
            send(socket, "initialize", buildJsonObject {}, 20)
            withTimeout(5000) { while (!runtime.roots.getValue("harness").resolve("initialization-started").exists()) delay(10) }
        }
        socket { socket, messages ->
            send(socket, "initialize", buildJsonObject { put("protocolVersion", 2) }, 22)
            assertContains(response(messages, 22).last().toString(), "Pending native setup differs")
            send(socket, "initialize", buildJsonObject {}, 21)
            assertEquals("READY", runtime.status().phase)
            hold.deleteExisting()
            val transferred = withTimeout(5000) { response(messages, 21) }
            assertTrue("result" in transferred.last())
            assertTrue(transferred.none { it["id"] == JsonPrimitive(20) }, "A previous owner's response must not reach the new connection")
            send(socket, "initialize", buildJsonObject {})
            assertTrue("result" in response(messages).last())
            send(socket, "session/new", buildJsonObject {})
            assertTrue("result" in response(messages).last())
        }
    }

    @Test fun `same owner replaces its observer and transfers pending setup without repeating native work`(): Unit = runBlocking {
        val hold = directory.root.toPath().resolve("hold-initialize")
        hold.writeText("")
        val staleTurn = Uuid.random().toString()
        socket { previous, _ ->
            send(previous, "initialize", buildJsonObject {}, 20)
            withTimeout(5000) { while (!runtime.roots.getValue("harness").resolve("initialization-started").exists()) delay(10) }
            socket { current, messages ->
                send(current, "initialize", buildJsonObject {}, 21)
                hold.deleteExisting()
                val initialized = response(messages, 21)
                assertTrue("result" in initialized.last())
                assertTrue(initialized.none { it["id"] == JsonPrimitive(20) })
                // A delayed frame from the old transport must not become a native prompt.
                previous.send(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", 70); put("method", "session/prompt"); put("params", prompt(staleTurn, "stale-observer"))
                }.toString())
                send(current, "session/new", buildJsonObject { put("cwd", "/workspace"); putJsonArray("mcpServers") {} }, 22)
                assertTrue("result" in response(messages, 22).last())
                val turn = Uuid.random().toString()
                send(current, "session/prompt", prompt(turn, "current-observer"), 23)
                assertEquals("end_turn", response(messages, 23).last().getValue("result").jsonObject.getValue("stopReason").jsonPrimitive.content)
                assertNull(runtime.turn(staleTurn))
                assertEquals("1", runtime.roots.getValue("harness").resolve("prompt-count").readText())
            }
        }
    }

    @Test fun `replacement observer does not receive an old prompt reply with a reused RPC id`(): Unit = runBlocking {
        val turn = Uuid.random().toString()
        socket { previous, previousMessages ->
            send(previous, "initialize", buildJsonObject {}, 1); response(previousMessages, 1)
            send(previous, "session/new", buildJsonObject { put("cwd", "/workspace"); putJsonArray("mcpServers") {} }, 2); response(previousMessages, 2)
            send(previous, "session/prompt", prompt(turn, "slow-turn"), 3)
            withTimeout(2000) { while (runtime.turn(turn) == null) delay(10) }
            socket { current, messages ->
                send(current, "initialize", buildJsonObject {}, 3)
                assertTrue("protocolVersion" in response(messages, 3).last().getValue("result").jsonObject)
                withTimeout(3000) { while (runtime.turn(turn)?.complete != true) delay(10) }
                delay(150)
                while (true) {
                    val message = messages.tryReceive().getOrNull() ?: break
                    assertNotEquals(JsonPrimitive(3), message["id"], "An old prompt response must remain in its receipt")
                }
                assertTrue(assertNotNull(runtime.turn(turn)).complete)
                assertEquals("1", runtime.roots.getValue("harness").resolve("prompt-count").readText())
            }
        }
    }

    @Test fun `detached permission is fenced and can complete its original turn`() = runBlocking {
        val turn = Uuid.random().toString()
        var permission: JsonObject? = null
        socket { socket, messages ->
            send(socket, "session/prompt", prompt(turn, "permission"))
            permission = messages.receive()
            assertContains(permission.toString(), "boscaRuntimeRequestId")
            call("/v1/archive").use { assertEquals(409, it.code) }
        }
        call("/v1/archive", headers = mapOf("X-Bosca-Epoch" to "1")).use { assertEquals(409, it.code) }
        call("/v1/archive", headers = mapOf("Authorization" to "Bearer wrong")).use { assertEquals(401, it.code) }
        call("/v1/archive", headers = mapOf("X-Bosca-Generation" to "2")).use { assertEquals(401, it.code) }
        call("/v1/permission", buildJsonObject {
            put("jsonrpc", "2.0"); put("id", requireNotNull(permission).getValue("id"))
            putJsonObject("result") { putJsonObject("outcome") { put("outcome", "selected"); put("optionId", "allow") } }
        }.toString()).use { assertEquals(200, it.code) }
        withTimeout(5000) { while (runtime.turn(turn)?.complete != true) delay(10) }
        assertEquals("permission", runtime.roots.getValue("harness").resolve("sessions/test.json").readText())
        assertEquals(0, runtime.status().pendingPermissions)
    }

    @Test fun `cancellation settles permission waits and drops late decisions`() = runBlocking {
        for (text in listOf("wait", "permission")) socket { socket, messages ->
            send(socket, "session/new", buildJsonObject {}); response(messages)
            val turn = Uuid.random().toString()
            send(socket, "session/prompt", prompt(turn, text), 20)
            withTimeout(5000) { while (runtime.status().activeTurn == null) delay(5) }
            if (text == "permission") assertEquals("session/request_permission", messages.receive().string("method"))
            runtime.cancel("another-turn", epoch)
            assertEquals(turn, runtime.status().activeTurn)
            call("/v1/cancel", "{\"turnId\":\"$turn\"}").use { assertEquals(200, it.code) }
            assertEquals("cancelled", response(messages, 20).last().getValue("result").jsonObject.string("stopReason"))
            assertEquals(0, runtime.status().pendingPermissions)
            assertNull(runtime.status().activeTurn)
            assertTrue(assertNotNull(runtime.turn(turn)).complete)
            runtime.receive(Json.parseToJsonElement("{\"jsonrpc\":\"2.0\",\"id\":\"approval-1\",\"result\":{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"allow\"}}}").jsonObject, epoch)
        }
    }

    @Test fun `secrets are redacted before delivery and journaling and failed archive retains local state`() = runBlocking {
        val turn = Uuid.random().toString()
        socket { socket, messages ->
            send(socket, "session/prompt", prompt(turn, "leak"))
            val output = response(messages).toString()
            assertContains(output, "[redacted]"); assertFalse(output.contains(secret))
        }
        assertFalse(runtime.control.resolve("$turn.jsonl").readText().contains(secret))
        val retained = runtime.roots.getValue("workspace").resolve("retained")
        retained.writeText(secret)
        assertFailsWith<IllegalArgumentException> { runtime.archive(epoch) }
        assertEquals(secret, retained.readText())
        assertEquals("DRAINING", runtime.status().phase)
        retained.writeText("safe content")
        runtime.archive(epoch)
        assertEquals("ARCHIVED", runtime.status().phase)
    }

    @Test fun `ownership is rechecked when delayed control requests reach the runtime`() = runBlocking {
        val previous = epoch
        runtime.claim(++epoch)
        suspend fun rejected(action: suspend () -> Unit) {
            assertEquals("Stale runtime owner", assertFailsWith<IllegalStateException> { action() }.message)
        }
        rejected { runtime.start(previous) }
        rejected { runtime.configure(emptyList(), previous) }
        rejected { runtime.archive(previous) }
        rejected { runtime.restore(directory.root.toPath().resolve("absent.tar.gz"), "checksum", false, previous) }
        assertEquals("READY", runtime.status().phase)
        assertFalse(runtime.control.resolve("archive.tar.gz").exists())

        socket { socket, messages ->
            send(socket, "session/new", buildJsonObject {}); response(messages)
            val turn = Uuid.random().toString()
            send(socket, "session/prompt", prompt(turn, "wait"), 20)
            withTimeout(5000) { while (runtime.status().activeTurn == null) delay(5) }
            rejected { runtime.cancel(turn, previous) }
            assertEquals(turn, runtime.status().activeTurn)
            assertFalse(assertNotNull(runtime.turn(turn)).complete)
            runtime.cancel(turn, epoch)
            assertEquals("cancelled", response(messages, 20).last().getValue("result").jsonObject.string("stopReason"))
        }
        val receipt = runtime.archive(epoch)
        rejected { runtime.archive(previous) }
        assertEquals(receipt, runtime.archive(epoch))
    }

    @Test fun `HTTP cancellation delayed after admission cannot cancel a replacement owners turn`(): Unit = runBlocking {
        val receiving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        server.application.receivePipeline.intercept(ApplicationReceivePipeline.Before) {
            if (call.request.path() == "/v1/cancel" && !receiving.isCompleted) {
                receiving.complete(Unit)
                release.await()
            }
        }
        socket { socket, messages ->
            send(socket, "session/new", buildJsonObject {}); response(messages)
            val turn = Uuid.random().toString()
            send(socket, "session/prompt", prompt(turn, "wait"), 20)
            withTimeout(5000) { while (runtime.status().activeTurn == null) delay(5) }
            val delayed = async(Dispatchers.IO) { call("/v1/cancel", "{\"turnId\":\"$turn\"}") }
            try {
                withTimeout(5000) { receiving.await() }
                epoch++
                call("/v1/claim").use { assertEquals(200, it.code) }
            } finally { release.complete(Unit) }
            delayed.await().use {
                assertEquals(409, it.code)
                assertEquals("Stale runtime owner", it.body.string())
            }
            assertEquals(turn, runtime.status().activeTurn)
            assertFalse(assertNotNull(runtime.turn(turn)).complete)
            call("/v1/cancel", "{\"turnId\":\"$turn\"}").use { assertEquals(200, it.code) }
            withTimeout(5000) { while (runtime.turn(turn)?.complete != true) delay(5) }
            assertEquals("cancelled", assertNotNull(runtime.turn(turn)).messages.last().getValue("result").jsonObject.string("stopReason"))
        }
    }
}
