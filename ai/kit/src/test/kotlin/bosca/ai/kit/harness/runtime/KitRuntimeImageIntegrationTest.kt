package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.KitHarnessConfiguration
import bosca.ai.kit.harness.KitRuntimeSession
import bosca.ai.kit.harness.RuntimeVersions
import bosca.ai.kit.harness.acp.AcpSessionContinuity
import bosca.ai.kit.harness.acp.AcpWebSocketTransportFactory
import bosca.ai.kit.harness.acp.KitAcpConnection
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.github.dockerjava.api.model.Capability
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.DockerClientFactory
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.GZIPInputStream
import kotlin.test.*

/** Release gate for the actual native image; the ACP fixture makes no provider requests. */
class KitRuntimeImageIntegrationTest {
    private val json = Json
    private val client = KitRuntimeClient(json)
    private val http = java.net.http.HttpClient.newHttpClient()
    private val sessionId = UUID.random()
    private val principalId = UUID.random()
    private val swarmLayout = (System.getenv("KIT_RUNTIME_LAYOUT")?.takeIf(String::isNotBlank) ?: "standard").also { require(it in setOf("standard", "swarm")) } == "swarm"
    private val workspace get() = if (swarmLayout) "/state/session/workspace" else "/workspace"
    private fun state(name: String) = if (swarmLayout) "/state/session/$name" else "/state/$name"
    private var approvals = 0
    private lateinit var imagePlatform: String
    private var connectionAttempts = 0
    private val operations = ClientOperationsFactory { _, _ ->
        object : ClientSessionOperations {
            override suspend fun requestPermissions(toolCall: SessionUpdate.ToolCallUpdate, permissions: List<PermissionOption>, _meta: JsonElement?): RequestPermissionResponse {
                approvals++
                return RequestPermissionResponse(RequestPermissionOutcome.Selected(PermissionOptionId("allow")))
            }
            override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit
        }
    }

    @Test
    fun `native image isolates processes and restores a warm ACP session with current credentials`(): Unit = runBlocking {
        val image = requireNotNull(System.getenv("KIT_RUNTIME_IMAGE")?.takeIf { it.isNotBlank() }) { "Set KIT_RUNTIME_IMAGE to an already built image" }
        require(image.none(Char::isWhitespace))
        val imageInfo = DockerClientFactory.instance().client().inspectImageCmd(image).exec()
        imagePlatform = "${requireNotNull(imageInfo.os)}/${requireNotNull(imageInfo.arch)}"
        val family = System.getenv("KIT_RUNTIME_FAMILY")
        require(family in listOf("codex", "claude")) { "Set KIT_RUNTIME_FAMILY to codex or claude" }
        val cliVersion = requireNotNull(System.getenv("KIT_RUNTIME_VERSION")?.takeIf { it.isNotBlank() }) { "Set KIT_RUNTIME_VERSION to the image's CLI version" }
        val descriptor = json.parseToJsonElement(Files.readString(Path.of(if (family == "claude") "runtime/claude/compatibility.json" else "runtime/compatibility.json"))).jsonObject
        val versions = RuntimeVersions(cliVersion, descriptor.getValue("adapter").jsonPrimitive.content.substringAfterLast('@'),
            descriptor.getValue("harness").jsonPrimitive.content.substringAfterLast('@'), descriptor.getValue("statePolicy").jsonPrimitive.content)
        val deploymentMetadata = requireNotNull(imageInfo.config?.labels?.get("io.bosca.kit.versions")) { "Image is missing its deployment metadata" }
        assertEquals(versions, json.decodeFromString(RuntimeVersions.serializer(), deploymentMetadata), "Deployment defaults must describe the installed runtime")
        val testImage = ImageFromDockerfile("bosca-kit-contract-${UUID.random()}", true)
            .withBuildImageCmdModifier { command -> command.withPlatform(imagePlatform) }
            .withFileFromString("Dockerfile", "FROM $image\nUSER root\nCOPY tests /opt/test/tests/\nCOPY compatibility.json /opt/test/compatibility.json\nCOPY claude.json /opt/test/claude/compatibility.json\nUSER node\n")
            .withFileFromPath("tests", Path.of("runtime/tests").toAbsolutePath())
            .withFileFromPath("compatibility.json", Path.of("runtime/compatibility.json").toAbsolutePath())
            .withFileFromPath("claude.json", Path.of("runtime/claude/compatibility.json").toAbsolutePath())
        val archive = Files.createTempFile("kit-image-", ".tar.gz")
        val oldToken = UUID.random().toString()
        val newToken = UUID.random().toString()
        val oldCredential = "bsk_fixture_generation_one"
        val newCredential = "bsk_fixture_generation_two"
        val parameters = SessionCreationParameters("/workspace", listOf(McpServer.Stdio("bosca", "/usr/local/bin/bosca", listOf("harness", "mcp", "bosca"), emptyList())))
        try {
            withTimeout(180_000) {
                var nativeId: String
                var archiveSha: String
                var firstInstance: String
                container(testImage, family, 1, oldToken, oldCredential).use { first ->
                    first.start()
                    var session = session(first, 1, versions)
                    assertIsolation(first)
                    val adapter = first.execInContainer("node", "/opt/test/tests/check_adapter.mjs", "/opt/runtime")
                    assertEquals(0, adapter.exitCode, adapter.stderr)
                    val mcp = first.execInContainer("node", "/opt/test/tests/check_cli.mjs", "/usr/local/bin/bosca", family)
                    assertEquals(0, mcp.exitCode, mcp.stderr)
                    val prepared = client.claim(session, oldToken)
                    assertEquals("PREPARED", prepared.phase)
                    firstInstance = prepared.instanceId
                    session = session.copy(runtimeInstanceId = firstInstance)
                    assertEquals(versions, client.status(session, oldToken).versions)
                    assertEquals(401, request(session, "wrong-token", "/v1/start", "POST").statusCode())
                    assertEquals(401, request(session.copy(generation = 2), oldToken, "/v1/start", "POST").statusCode())
                    assertEquals(409, request(session.copy(epoch = 2), oldToken, "/v1/start", "POST").statusCode())
                    assertEquals(409, request(session.copy(runtimeInstanceId = UUID.random().toString()), oldToken, "/v1/start", "POST").statusCode())
                    val capabilities = json.parseToJsonElement(request(session, oldToken, "/v1/capabilities").body()).jsonObject
                    assertEquals(versions.statePolicy, capabilities.getValue("statePolicy").jsonPrimitive.content)
                    assertEquals(versions.adapter, capabilities.getValue("adapter").jsonPrimitive.content)
                    assertEquals(versions.harness, capabilities.getValue("harness").jsonPrimitive.content)
                    assertEquals(workspace, capabilities.getValue("paths").jsonObject.getValue("workspace").jsonPrimitive.content)
                    assertEquals("READY", client.start(session, oldToken).phase)
                    assertTrue(client.configureMcp(session, oldToken, emptyList()).isEmpty())
                    assertNativeMcp(first)
                    HttpClient(CIO) { install(WebSockets) }.use { acpHttp ->
                        var nativePid: String
                        connect(acpHttp, session, oldToken).use { connection ->
                            val selection = connection.openSession(parameters, operations)
                            assertEquals(AcpSessionContinuity.NEW, selection.continuity)
                            nativeId = selection.session.sessionId.value
                            assertEquals(workspace, json.parseToJsonElement(read(first, state("harness/last-setup.json"))).jsonObject.getValue("cwd").jsonPrimitive.content)
                            assertTurn(connection, selection.session, session, "first")
                            nativePid = read(first, state("harness/process-pid"))
                        }
                        connect(acpHttp, session, oldToken).use { connection ->
                            val selection = connection.openSession(parameters, operations, nativeId)
                            assertEquals(AcpSessionContinuity.LOADED, selection.continuity)
                            assertEquals(nativeId, selection.session.sessionId.value)
                            assertTurn(connection, selection.session, session, "permission")
                            assertEquals(1, approvals)
                            assertTurn(connection, selection.session, session, "spawn-writer")
                            assertEquals(nativePid, read(first, state("harness/process-pid")))
                            val writerPid = read(first, state("harness/writer-pid"))
                            write(first, "$workspace/artifact.txt", "saved workspace")
                            write(first, "$workspace/repository/src/Main.kt", "nested workspace")
                            val receipt = client.archive(session, oldToken)
                            archiveSha = receipt.sha256
                            assertEquals(1, receipt.formatVersion)
                            assertEquals("ARCHIVED", client.status(session, oldToken).phase)
                            assertEquals(503, request(session, oldToken, "/readyz").statusCode())
                            for (pid in listOf(nativePid, writerPid)) {
                                assertTrue(pid.matches(Regex("[0-9]+")))
                                val probe = first.execInContainer("node", "-e", "try { process.kill(Number(process.argv[1]), 0); process.exit(1); } catch (e) { if (e.code !== 'ESRCH') throw e; }", pid)
                                assertEquals(0, probe.exitCode, "Archive left a native writer alive: ${probe.stderr}")
                            }
                            client.download(session, oldToken, archive, 16L * 1024 * 1024)
                            assertEquals(receipt.size, Files.size(archive))
                            assertEquals(archiveSha, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)).joinToString("") { "%02x".format(it) })
                            val contents = GZIPInputStream(Files.newInputStream(archive)).use { it.readBytes().decodeToString() }
                            assertFalse(contents.contains(oldToken))
                            assertFalse(contents.contains(oldCredential))
                            assertFalse(contents.contains("mcp-processes.json"))
                        }
                    }
                }
                container(testImage, family, 2, newToken, newCredential).use { second ->
                    second.start()
                    var session = session(second, 2, versions).copy(archiveSha256 = archiveSha)
                    val prepared = client.claim(session, newToken)
                    assertEquals("PREPARED", prepared.phase)
                    assertNotEquals(firstInstance, prepared.instanceId, "Restoration must not restore the old process identity")
                    session = session.copy(runtimeInstanceId = prepared.instanceId)
                    assertFailsWith<KitRuntimeReplacedException> { client.start(session.copy(runtimeInstanceId = firstInstance), newToken) }
                    assertEquals(401, request(session, oldToken, "/v1/start", "POST").statusCode())
                    assertEquals("RESTORED", client.restore(session, newToken, archive).phase)
                    assertEquals("saved workspace", read(second, "$workspace/artifact.txt"))
                    assertEquals("nested workspace", read(second, "$workspace/repository/src/Main.kt"))
                    assertEquals("spawn-writer", read(second, state("harness/" + if (family == "claude") "projects/fixture/test.jsonl" else "sessions/test.json")))
                    assertEquals("READY", client.start(session, newToken).phase)
                    client.configureMcp(session, newToken, emptyList())
                    val processes = read(second, state("control/mcp-processes.json"))
                    assertTrue(processes.contains(newCredential))
                    assertFalse(processes.contains(oldCredential))
                    assertNativeMcp(second)
                    HttpClient(CIO) { install(WebSockets) }.use { acpHttp ->
                        connect(acpHttp, session, newToken).use { connection ->
                            val selection = connection.openSession(parameters, operations, nativeId)
                            assertEquals(AcpSessionContinuity.LOADED, selection.continuity)
                            assertEquals(nativeId, selection.session.sessionId.value)
                            assertTurn(connection, selection.session, session, "restored")
                            val turnId = UUID.random().toString()
                            val events = async { connection.prompt(selection.session, listOf(ContentBlock.Text("wait")), correlation(session, turnId)).toList() }
                            while (client.turn(session, newToken, turnId) == null) delay(20)
                            client.cancel(session, newToken, turnId)
                            assertEquals(StopReason.CANCELLED, assertIs<Event.PromptResponseEvent>(events.await().last()).response.stopReason)
                            val receipt = requireNotNull(client.turn(session, newToken, turnId))
                            assertTrue(receipt.complete)
                            assertEquals("cancelled", receipt.messages.last().getValue("result").jsonObject.getValue("stopReason").jsonPrimitive.content)
                        }
                    }
                    client.archive(session, newToken)
                }
                assertBlockedInputShutdown(testImage, family, versions)
            }
        } finally { Files.deleteIfExists(archive) }
    }

    private suspend fun assertBlockedInputShutdown(image: ImageFromDockerfile, family: String?, versions: RuntimeVersions) {
        val token = UUID.random().toString()
        container(image, family, 3, token, "bsk_fixture_blocked_input").use { blocked ->
            blocked.start()
            var session = session(blocked, 3, versions)
            session = session.copy(runtimeInstanceId = client.claim(session, token).instanceId)
            write(blocked, state("hold-initialize"), "")
            client.start(session, token)
            HttpClient(CIO) { install(WebSockets) }.use { acp ->
                val socket = acp.webSocketSession(requireNotNull(session.endpoint).replaceFirst("http", "ws") + "/acp") {
                    header("Authorization", "Bearer $token")
                    header("X-Bosca-Generation", session.generation)
                    header("X-Bosca-Epoch", session.epoch)
                    header("X-Bosca-Instance", requireNotNull(session.runtimeInstanceId))
                }
                try {
                    socket.send(Frame.Text("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""))
                    withTimeout(5000) {
                        while (blocked.execInContainer("test", "-f", state("harness/initialization-started")).exitCode != 0) delay(25)
                    }
                    val turnId = UUID.random().toString()
                    socket.send(Frame.Text(buildJsonObject {
                        put("jsonrpc", "2.0"); put("id", 2); put("method", "session/prompt")
                        putJsonObject("params") {
                            put("sessionId", "native-test")
                            putJsonArray("prompt") { add(buildJsonObject { put("type", "text"); put("text", "x".repeat(4 * 1024 * 1024)) }) }
                            put("_meta", correlation(session, turnId))
                        }
                    }.toString()))
                    withTimeout(10_000) {
                        while (blocked.execInContainer("test", "-s", state("control/$turnId.jsonl")).exitCode != 0) delay(25)
                    }
                    assertNull(withTimeoutOrNull(500) { acp.get(session.endpoint + "/healthz") }, "The fixture must block a native pipe write")
                    val docker = DockerClientFactory.instance().client()
                    val stopping = System.nanoTime()
                    withContext(Dispatchers.IO) { docker.stopContainerCmd(blocked.containerId).withTimeout(15).exec() }
                    val stopped = assertNotNull(docker.inspectContainerCmd(blocked.containerId).exec().state)
                    System.err.println("Blocked-input SIGTERM: exit=${stopped.exitCodeLong}, elapsedMs=${(System.nanoTime() - stopping) / 1_000_000}")
                    assertEquals(false, stopped.running)
                    assertTrue(stopped.exitCodeLong == 0L || stopped.exitCodeLong == 143L,
                        "Blocked input required forced termination: ${stopped.exitCodeLong}")
                } finally { socket.cancel() }
            }
        }
    }

    private fun container(image: ImageFromDockerfile, family: String?, generation: Long, token: String, credential: String): KitRuntimeImageContainer {
        val owned = mapOf("BOSCA_RUNTIME_TOKEN" to token, "BOSCA_RUNTIME_EXPIRES" to (System.currentTimeMillis() / 1000 + 600).toString(), "BOSCA_TOKEN" to credential)
        val source = if (swarmLayout) ImageFromDockerfile("bosca-kit-secret-fixture-${UUID.random()}", true)
            .withBuildImageCmdModifier { command -> command.withPlatform(imagePlatform) }
            .withFileFromString("Dockerfile", "FROM ${image.get()}\nUSER root\nCOPY --chown=node:node runtime.json /run/secrets/bosca-runtime.json\nRUN chmod 0400 /run/secrets/bosca-runtime.json\nUSER node\n")
            .withFileFromString("runtime.json", buildJsonObject { owned.forEach { (key, value) -> put(key, value) } }.toString()) else image
        return KitRuntimeImageContainer(source)
        .withEnv(mapOf("BOSCA_CHAT_SESSION_ID" to sessionId.toString(), "BOSCA_RUNTIME_GENERATION" to generation.toString(), "BOSCA_ENDPOINT" to "http://bosca.invalid/graphql",
            "BOSCA_ACP_COMMAND" to "[\"/usr/local/bin/node\",\"/opt/test/tests/fake_adapter.mjs\"]", "TEST_HARNESS_HOME" to state("harness"),
            "TEST_HARNESS_STATE_PATH" to if (family == "claude") "projects/fixture/test.jsonl" else "sessions/test.json") +
            if (swarmLayout) mapOf("HOME" to state("home"), "TMPDIR" to state("scratch"), "CODEX_HOME" to state("harness"), "CLAUDE_CONFIG_DIR" to state("harness"), "CLAUDE_CODE_TMPDIR" to state("scratch")) else owned)
        .withTmpFs(if (swarmLayout) mapOf("/state" to "rw,nosuid,nodev,size=335544320,mode=1777") else mapOf("/state" to "rw,nosuid,uid=1000,gid=1000", "/workspace" to "rw,nosuid,uid=0,gid=1000,mode=0770"))
        .withCreateContainerCmdModifier { command ->
            command.withPlatform(imagePlatform)
            command.hostConfig?.withReadonlyRootfs(true)?.withPrivileged(false)
            ?.withCapDrop(Capability.ALL)?.withSecurityOpts(listOf("no-new-privileges"))
            if (swarmLayout) { command.withWorkingDir("/state"); command.withCmd("--root", "/state/session", "--private-pid-namespace") }
        }
        .withExposedPorts(8080)
        .withLogConsumer { frame -> System.err.print(frame.utf8String) }
        .waitingFor(Wait.forHttp("/readyz").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(60)))
    }

    private fun session(container: KitRuntimeImageContainer, generation: Long, versions: RuntimeVersions): KitRuntimeSession {
        val endpoint = "http://${container.host}:${container.getMappedPort(8080)}"
        val configuration = KitHarnessConfiguration("kit", endpoint, "http://bosca.invalid/graphql", scopes = listOf("content:view"),
            image = "test", imageId = container.containerInfo.imageId, versions = versions)
        return KitRuntimeSession(sessionId, principalId, json.encodeToJsonElement(KitHarnessConfiguration.serializer(), configuration),
            JsonObject(emptyMap()), generation = generation, epoch = 1, endpoint = endpoint, idleAt = OffsetDateTime.now())
    }

    private fun assertIsolation(container: KitRuntimeImageContainer) {
        assertEquals("node", container.containerInfo.config.user)
        val host = container.containerInfo.hostConfig
        assertEquals(true, host.readonlyRootfs)
        assertEquals(false, host.privileged)
        assertTrue(Capability.ALL in host.capDrop)
        assertTrue(host.securityOpts.orEmpty().any { it.startsWith("no-new-privileges") })
        assertNotEquals("host", host.networkMode)
        assertNotEquals("host", host.pidMode)
        assertTrue(container.containerInfo.mounts.orEmpty().none { it.destination?.path == "/var/run/docker.sock" })
        if (swarmLayout) {
            assertTrue(container.containerInfo.config.env.orEmpty().none { it.startsWith("BOSCA_TOKEN=") || it.startsWith("BOSCA_RUNTIME_TOKEN=") })
            val privateState = container.execInContainer("stat", "-c", "%u:%a", "/state/session")
            assertEquals(0, privateState.exitCode, privateState.stderr)
            assertEquals("1000:700", privateState.stdout.trim())
        } else {
            val workspace = container.execInContainer("stat", "-c", "%u:%g:%a", "/workspace")
            assertEquals(0, workspace.exitCode, workspace.stderr)
            assertEquals("0:1000:770", workspace.stdout.trim())
        }
    }

    private suspend fun CoroutineScope.connect(http: HttpClient, session: KitRuntimeSession, token: String): KitAcpConnection {
        System.err.println("Image ACP connection ${++connectionAttempts}, generation ${session.generation}")
        return try {
            KitAcpConnection.connect(this, AcpWebSocketTransportFactory(http, URI.create(requireNotNull(session.endpoint).replaceFirst("http", "ws") + "/acp"),
                session.generation, session.epoch, session.runtimeInstanceId) { token })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            System.err.println("Image ACP failed: ${client.status(session, token)}")
            throw failure
        }
    }

    private suspend fun assertTurn(connection: KitAcpConnection, native: ClientSession, session: KitRuntimeSession, text: String) {
        val events = connection.prompt(native, listOf(ContentBlock.Text(text)), correlation(session, UUID.random().toString())).toList()
        assertEquals(StopReason.END_TURN, assertIs<Event.PromptResponseEvent>(events.last()).response.stopReason)
        assertEquals(listOf("saved:$text"), events.filterIsInstance<Event.SessionUpdateEvent>().mapNotNull { (it.update as? SessionUpdate.AgentMessageChunk)?.content as? ContentBlock.Text }.map { it.text })
    }

    private fun correlation(session: KitRuntimeSession, id: String) = buildJsonObject { put("turnId", id); put("generation", session.generation) }

    private fun request(session: KitRuntimeSession, token: String, path: String, method: String = "GET"): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create(session.endpoint + path)).timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer $token")
            .header("X-Bosca-Generation", session.generation.toString()).header("X-Bosca-Epoch", session.epoch.toString())
            .apply { session.runtimeInstanceId?.let { header("X-Bosca-Instance", it) } }
            .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())

    private fun read(container: KitRuntimeImageContainer, path: String): String = container.execInContainer("cat", path).let { assertEquals(0, it.exitCode, it.stderr); it.stdout.trim() }
    private fun write(container: KitRuntimeImageContainer, path: String, value: String) {
        val result = container.execInContainer("node", "-e", "const fs = require('node:fs'); fs.mkdirSync(require('node:path').dirname(process.argv[1]), {recursive:true}); fs.writeFileSync(process.argv[1], process.argv[2])", path, value)
        assertEquals(0, result.exitCode, result.stderr)
    }

    private fun assertNativeMcp(container: KitRuntimeImageContainer) {
        val result = container.execInContainer("node", "-e", """
            const {spawn} = require('node:child_process');
            const {createInterface} = require('node:readline');
            const child = spawn('/usr/local/bin/bosca', ['harness', 'mcp', 'bosca', '--control', process.argv[1]], {stdio:['pipe','pipe','inherit']});
            let listed = false;
            const send = message => child.stdin.write(JSON.stringify({jsonrpc:'2.0', ...message})+'\n');
            const timer = setTimeout(() => { child.kill('SIGKILL'); process.exit(1); }, 20000);
            createInterface({input:child.stdout}).on('line', line => {
              const response = JSON.parse(line);
              if (response.error) throw new Error(JSON.stringify(response.error));
              if (response.id === 1) {
                send({method:'notifications/initialized'});
                send({id:2, method:'tools/list', params:{}});
              } else if (response.id === 2) {
                console.log(JSON.stringify(response.result.tools.map(tool => tool.name)));
                listed = true;
                clearTimeout(timer); child.kill();
              }
            });
            child.on('exit', (code, signal) => process.exit(listed && (code === 0 || code === 143 || signal === 'SIGTERM') ? 0 : 1));
            send({id:1, method:'initialize', params:{protocolVersion:'2025-06-18', capabilities:{}, clientInfo:{name:'image-test',version:'1'}}});
        """.trimIndent(), state("control"))
        assertEquals(0, result.exitCode, result.stderr + result.stdout)
        val tools = json.parseToJsonElement(result.stdout.trim()).jsonArray.map { it.jsonPrimitive.content }
        assertTrue(tools.containsAll(listOf("content_metadata", "bosca_memory", "bosca_graphql")))
    }
}
