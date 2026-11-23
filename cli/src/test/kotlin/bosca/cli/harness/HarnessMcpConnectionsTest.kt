package bosca.cli.harness

import bosca.ai.agents.model.McpTransportType
import bosca.ai.kit.harness.KitMcpConnection
import bosca.ai.kit.harness.KitMcpSecretReference
import io.ktor.http.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.*
import kotlin.test.*

class HarnessMcpConnectionsTest {
    @get:Rule val directory = TemporaryFolder()
    private val secret = "fixture-external-secret"
    private val cli = "/usr/local/bin/bosca"

    @Test fun `HTTP probe resolves credentials only at the edge and retains the warm selection`() = runBlocking {
        val methods = CopyOnWriteArrayList<String>()
        val authorizations = CopyOnWriteArrayList<String>()
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            routing { route("/mcp") { handle {
                val authorization = call.request.headers["Authorization"].orEmpty()
                authorizations += authorization
                when {
                    authorization != "Bearer $secret" -> call.respondText(secret, status = HttpStatusCode.Unauthorized)
                    call.request.httpMethod == HttpMethod.Get -> call.respond(HttpStatusCode.MethodNotAllowed)
                    call.request.httpMethod == HttpMethod.Delete -> call.respond(HttpStatusCode.OK)
                    else -> {
                        val request = Json.parseToJsonElement(call.receiveText()).jsonObject
                        methods += request.getValue("method").jsonPrimitive.content
                        if ("id" !in request) call.respond(HttpStatusCode.Accepted) else call.respondText(buildJsonObject {
                            put("jsonrpc", "2.0"); put("id", request.getValue("id"))
                            putJsonObject("result") {
                                if (request.string("method") == "initialize") {
                                    put("protocolVersion", request.getValue("params").jsonObject.getValue("protocolVersion"))
                                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                                    putJsonObject("serverInfo") { put("name", "fixture"); put("version", "1") }
                                } else putJsonArray("tools") {}
                            }
                        }.toString(), ContentType.Application.Json)
                    }
                }
            } } }
        }.start()
        try {
            val url = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}/mcp"
            val plan = KitMcpConnection("docs", "Documents", McpTransportType.STREAMABLE_HTTP, true, url = url,
                headers = mapOf("Authorization" to KitMcpSecretReference("DOCS_ACCESS", "Bearer ")))
            val secrets = mutableSetOf<String>()
            val connections = HarnessMcpConnections(directory.root.toPath(), mapOf("DOCS_ACCESS" to secret), secrets, cli)
            val result = connections.configure(listOf(plan), directory.root.toPath(), "session")
            assertTrue(result.single().ready, result.toString())
            assertTrue(methods.containsAll(listOf("initialize", "tools/list")))
            assertTrue(authorizations.all { it == "Bearer $secret" })
            assertEquals(setOf(secret), secrets)
            assertFalse(connections.plans.toString().contains(secret))
            val config = buildJsonObject { put("name", "docs"); put("type", "http"); put("url", url); putJsonArray("headers") {} }
            assertContains(connections.apply(JsonArray(listOf(config))).toString(), "Bearer $secret")
            assertEquals(JsonArray(emptyList()), config["headers"])
            val count = methods.size
            assertEquals(result, connections.configure(listOf(plan), directory.root.toPath(), "session"))
            assertEquals(count, methods.size)
            assertFailsWith<IllegalArgumentException> { connections.configure(emptyList(), directory.root.toPath(), "session") }
            assertFailsWith<IllegalArgumentException> { connections.apply(JsonArray(listOf(JsonObject(config + ("url" to JsonPrimitive("$url/changed")))))) }
            val rotated = HarnessMcpConnections(directory.root.toPath(), mapOf("DOCS_ACCESS" to "rotated-external-secret"), mutableSetOf(), cli)
            assertFalse(rotated.configure(listOf(plan), directory.root.toPath(), "session").single().ready)
            assertContains(authorizations, "Bearer rotated-external-secret")
            val failures = HarnessMcpConnections(directory.root.toPath(), emptyMap(), mutableSetOf(), cli).configure(
                listOf(plan.copy(key = "missing"), plan.copy(key = "rejected", headers = emptyMap())), directory.root.toPath(), "session")
            assertTrue(failures.all { !it.ready })
            assertFalse(failures.toString().contains(secret))
            assertFalse(failures.toString().contains(url))
        } finally { server.stop(0, 1000) }
    }

    @Test fun `stdio probe and launch settings contain only selected credentials`() = runBlocking {
        val node = System.getenv("PATH").split(':').map { Path.of(it, "node") }.first { it.isExecutable() }
        val fixture = Path.of("../ai/kit/runtime/tests/fake_mcp.mjs").toAbsolutePath().normalize()
        val plan = KitMcpConnection("local", "Local", McpTransportType.STDIO, true, command = listOf(node.toString(), fixture.toString()),
            environment = mapOf("REQUIRED_SECRET" to KitMcpSecretReference("DOCS_ACCESS")))
        val connections = HarnessMcpConnections(directory.root.toPath(), mapOf("DOCS_ACCESS" to secret, "BOSCA_RUNTIME_TOKEN" to "private-control-token"), mutableSetOf(), cli)
        assertTrue(connections.configure(listOf(plan), directory.root.toPath(), "session").single().ready)
        val file = directory.root.toPath().resolve("mcp-processes.json")
        assertEquals(384, HarnessArchive.mode(file))
        assertContains(file.readText(), secret)
        assertFalse(file.readText().contains("private-control-token"))
        val config = buildJsonObject { put("name", "local"); put("command", cli); putJsonArray("args") { add("harness"); add("mcp"); add("local") }; putJsonArray("env") {} }
        val launch = connections.apply(JsonArray(listOf(config))).single().jsonObject
        assertEquals(listOf("harness", "mcp", "local", "--control", directory.root.toPath().toString()), launch.getValue("args").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(launch.toString().contains(secret))
        assertFailsWith<IllegalArgumentException> { connections.apply(JsonArray(listOf(JsonObject(config + ("command" to JsonPrimitive("/bin/sh")))))) }
        Unit
    }
}
