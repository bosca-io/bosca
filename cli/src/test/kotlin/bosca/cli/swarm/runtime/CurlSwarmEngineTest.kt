package bosca.cli.swarm.runtime

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*

class CurlSwarmEngineTest {
    @Test fun `engine body stays on stdin and cancellation terminates a blocked helper`(): Unit = runBlocking {
        val root = Files.createTempDirectory("swarm-engine-test-")
        try {
            val capture = root.resolve("capture.json")
            val script = """
                let input = '';
                process.stdin.on('data', value => input += value);
                process.stdin.on('end', () => {
                  require('fs').writeFileSync(process.argv[1], JSON.stringify({args: process.argv.slice(2), input}));
                  process.stdout.write('{}\n200');
                });
            """.trimIndent()
            val engine = CurlSwarmEngine("/private-test.sock", listOf("node", "-e", script, "--", capture.toString()))
            assertEquals(200, engine.request("POST", "/services/create", buildJsonObject { put("Data", "private-fixture-credential") },
                mapOf("X-Registry-Auth" to "private-registry-authorization")).status)
            val recorded = Json.parseToJsonElement(capture.readText()).jsonObject
            assertFalse(recorded.getValue("args").toString().contains("private-fixture-credential"))
            assertContains(recorded.getValue("input").jsonPrimitive.content, "private-fixture-credential")
            assertFalse(recorded.getValue("args").toString().contains("private-registry-authorization"))
            assertContains(recorded.getValue("input").jsonPrimitive.content, "X-Registry-Auth: private-registry-authorization")
            val pid = root.resolve("pid")
            val blocked = CurlSwarmEngine("/private-test.sock", listOf("node", "-e",
                "require('fs').writeFileSync(process.argv[1], process.pid.toString()); process.stdin.resume(); setInterval(() => {}, 1000);", "--", pid.toString()))
            val request = async { blocked.request("GET", "/info") }
            withTimeout(5000) { while (!pid.exists()) delay(10) }
            val process = ProcessHandle.of(pid.readText().toLong()).orElseThrow()
            withTimeout(2000) { request.cancelAndJoin(); while (process.isAlive) delay(10) }
            assertTrue(request.isCancelled)
        } finally { root.toFile().deleteRecursively() }
    }
}
