package bosca.cli.harness

import bosca.ai.agents.model.McpTransportType
import bosca.ai.kit.harness.KitMcpConnection
import bosca.cli.harness.egress.EgressCredentials
import bosca.cli.harness.egress.EgressDestination
import bosca.cli.harness.egress.FakeUpstreamProxy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class HarnessEgressTest {
    @get:Rule val directory = TemporaryFolder()
    private val session = "0b5f8f2e-5c0a-4d55-9a49-1b6f6d1c2e3f"
    private val token = "egress-token-fixture-value"
    private val egress = HarnessEgress(41234, HarnessEgress.noProxy("http://bosca-server:8080/graphql"))

    @Test fun `processes use the credential-free loopback forwarder and reach Bosca directly`() {
        val environment = egress.environment()
        for (key in listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")) assertEquals("http://127.0.0.1:41234", environment[key])
        assertEquals("bosca-server,localhost,127.0.0.1,::1", environment["NO_PROXY"])
        assertEquals(environment["NO_PROXY"], environment["no_proxy"])
        assertEquals("1", environment["NODE_USE_ENV_PROXY"])
        assertEquals(listOf("localhost", "127.0.0.1", "::1"), HarnessEgress.noProxy(null))
        assertEquals("http://127.0.0.1:41234", egress.proxyFor("https://mcp.example.com/mcp"))
        assertNull(egress.proxyFor("http://BOSCA-SERVER:8080/graphql"))
        assertNull(egress.proxyFor("http://127.0.0.1:9000/mcp"))
        assertNull(egress.proxyFor("http://[::1]:9000/mcp"))
    }

    @Test fun `the egress proxy is configured only when both its origin and token are present`() {
        val base = mapOf("BOSCA_CHAT_SESSION_ID" to session)
        assertNull(HarnessEgress.settings(base))
        assertNull(HarnessEgress.settings(base + ("BOSCA_EGRESS_PROXY" to "http://kit-egress:3128")))
        assertNull(HarnessEgress.settings(base + ("BOSCA_EGRESS_TOKEN" to token)))
        assertEquals(EgressDestination("kit-egress", 3128) to EgressCredentials(session, token),
            HarnessEgress.settings(base + mapOf("BOSCA_EGRESS_PROXY" to "http://kit-egress:3128", "BOSCA_EGRESS_TOKEN" to token)))
        for (proxy in listOf("http://user:$token@kit-egress:3128", "https://kit-egress:3128", "http://kit-egress:3128/path", "http://kit-egress")) {
            assertFailsWith<IllegalArgumentException>(proxy) { HarnessEgress.settings(base + mapOf("BOSCA_EGRESS_PROXY" to proxy, "BOSCA_EGRESS_TOKEN" to token)) }
        }
    }

    @Test fun `the harness process receives proxy settings but never the egress token`() = runBlocking {
        val roots = HarnessArchive.ROOTS.associateWith { directory.root.toPath().resolve(it) }
        val output = directory.root.toPath().resolve("environment.txt")
        val runtime = HarnessRuntime(session, 1, "a".repeat(32), System.currentTimeMillis() / 1000 + 300,
            listOf("/bin/sh", "-c", "/usr/bin/env > '$output.tmp' && mv '$output.tmp' '$output' && exec /bin/cat"),
            roots, directory.root.toPath().resolve("control"), bosca.ai.kit.harness.RuntimeVersions("test", "test", "test", "codex-0.160.1"),
            environment = mapOf("BOSCA_EGRESS_TOKEN" to token, "BOSCA_EGRESS_PROXY" to "http://kit-egress:3128") + egress.environment(), egress = egress)
        try {
            runtime.start(0)
            withTimeout(10_000) { while (!output.exists()) delay(20) }
            val lines = output.readLines()
            assertContains(lines, "HTTPS_PROXY=http://127.0.0.1:41234")
            assertContains(lines, "NO_PROXY=bosca-server,localhost,127.0.0.1,::1")
            assertContains(lines, "NODE_USE_ENV_PROXY=1")
            assertFalse(output.readText().contains(token))
            assertFalse(output.readText().contains("BOSCA_EGRESS"))
        } finally { runtime.close() }
    }

    @Test fun `remote MCP probes tunnel through the forwarder without proxy credentials`() = runBlocking {
        FakeUpstreamProxy("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").use { forwarder ->
            val connections = HarnessMcpConnections(directory.root.toPath(), mapOf("BOSCA_EGRESS_TOKEN" to token), mutableSetOf(), "/usr/local/bin/bosca",
                HarnessEgress(forwarder.port, HarnessEgress.noProxy(null)))
            val plan = KitMcpConnection("docs", "Documents", McpTransportType.STREAMABLE_HTTP, true, url = "https://mcp.example.test/mcp")
            assertFalse(connections.configure(listOf(plan), directory.root.toPath(), session).single().ready)
            val head = forwarder.heads.first().split("\r\n")
            assertEquals("CONNECT mcp.example.test:443 HTTP/1.1", head.first())
            assertTrue(head.none { it.startsWith("Proxy-Authorization", ignoreCase = true) })
            assertFalse(forwarder.heads.joinToString().contains(token))
        }
    }

    @Test fun `packaged MCP processes launch with the forwarder settings`() = runBlocking {
        val node = System.getenv("PATH").split(':').map { Path.of(it, "node") }.first { it.isExecutable() }
        val fixture = Path.of("../ai/kit/runtime/tests/fake_mcp.mjs").toAbsolutePath().normalize()
        val plan = KitMcpConnection("local", "Local", McpTransportType.STDIO, true, command = listOf(node.toString(), fixture.toString()),
            environment = mapOf("REQUIRED_SECRET" to bosca.ai.kit.harness.KitMcpSecretReference("DOCS_ACCESS")))
        val connections = HarnessMcpConnections(directory.root.toPath(), mapOf("DOCS_ACCESS" to "fixture-external-secret", "BOSCA_EGRESS_TOKEN" to token),
            mutableSetOf(), "/usr/local/bin/bosca", egress)
        assertTrue(connections.configure(listOf(plan), directory.root.toPath(), session).single().ready)
        val launch = directory.root.toPath().resolve("mcp-processes.json").readText()
        assertContains(launch, "\"HTTPS_PROXY\":\"http://127.0.0.1:41234\"")
        assertContains(launch, "\"NODE_USE_ENV_PROXY\":\"1\"")
        assertFalse(launch.contains(token))
    }
}
