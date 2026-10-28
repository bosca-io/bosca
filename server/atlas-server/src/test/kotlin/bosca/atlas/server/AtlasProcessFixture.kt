package bosca.atlas.server

import bosca.test.resources.SharedMeilisearchContainer
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedPostgreSQLContainer
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Production Atlas processes sharing isolated database, queue, search, and object-storage fixtures. */
internal class AtlasProcessFixture : AutoCloseable {
    private val postgres = SharedPostgreSQLContainer().withDatabaseName("atlas_git")
    private val nats = SharedNatsContainer()
    private val search = SharedMeilisearchContainer()
    val directory = kotlin.io.path.createTempDirectory("atlas-git-").toFile()
    private val processes = mutableListOf<Process>()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val workspace = File(requireNotNull(System.getProperty("workspaceRoot")))

    init {
        postgres.start()
        nats.start()
        search.start()
    }

    fun start(name: String, flags: Map<String, String> = emptyMap()): Pair<String, String> {
        val apiPort = freePort()
        var uiPort = freePort()
        while (apiPort == uiPort) uiPort = freePort()
        val api = "http://localhost:$apiPort"
        val ui = "http://localhost:$uiPort"
        val template = File(workspace, "server/atlas-server/src/main/resources/atlas-server.yaml").readText()
        val storageType = "    type: \"\$STORAGE_TYPE:s3\""
        require(storageType in template)
        val config = File(directory, "$name.yaml")
        config.writeText(template.replace(storageType,
            "    type: fs\n    basePath: ${JsonPrimitive(File(directory, "storage").absolutePath)}")
            .replace("cookie-prefixes: []", "cookie-prefixes:\n    - prefix: _atlas_test\n      domains: [localhost]") +
            "\ninitialization:\n  password:\n    admin: atlas-admin-password\n    sa: atlas-sa-password\n")
        val log = File(directory, "$name.log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "--enable-native-access=ALL-UNNAMED", "-cp", requireNotNull(System.getProperty("atlasServerClasspath")),
            "bosca.atlas.server.ApplicationKt", "-config=${config.absolutePath}",
        ).directory(directory).apply {
            environment().putAll(mapOf(
                "ATLAS_HTTP_PORT" to apiPort.toString(), "ATLAS_UI_HTTP_PORT" to uiPort.toString(),
                "ATLAS_DEVELOPMENT" to "true", "BML_AUTH_COOKIE_PREFIX" to "_atlas_test",
                "DATABASE_URL" to postgres.jdbcUrl, "DATABASE_USER" to postgres.username,
                "DATABASE_PASSWORD" to postgres.password, "NATS_HOST" to nats.url.removePrefix("nats://"),
                "NATS_USERNAME" to nats.username, "NATS_PASSWORD" to nats.password, "NATS_TOKEN" to "",
                "MEILISEARCH_URL" to search.url, "MEILISEARCH_API_KEY" to search.apiKey,
                "MEILISEARCH_INDEX_PREFIX" to search.namespace, "APP_URL" to ui, "GIT_URL" to api,
                "GIT_UI_URL" to ui, "APP_ALLOWED_ORIGIN" to ui, "OAUTH2_ALLOWED_REDIRECT" to ui,
                "SECURITY_AUTO_VERIFY" to "true", "GRAPHQL_INTROSPECTION_ENABLED" to "true",
                "WELCOME_EMAIL_URL" to "", "ATLAS_RUNNER_CONCURRENCY" to "2",
            ) + flags)
            redirectErrorStream(true)
            redirectOutput(log)
        }.start()
        processes += process
        await("$name ready", log) {
            check(process.isAlive) { "Atlas process exited: ${log.readText().takeLast(16_000)}" }
            runCatching { request(api, "/api/v1/ready").statusCode() == 200 }.getOrDefault(false)
        }
        if (flags["ATLAS_UI_ENABLED"] != "false") {
            var observation = "No response"
            await("$name UI ready at $ui", log, { observation }) {
                try {
                    val response = request(ui, "/api/v1/ready")
                    observation = "HTTP ${response.statusCode()}: ${response.body().take(300)}"
                    response.statusCode() == 200
                } catch (failure: java.io.IOException) {
                    observation = failure.toString()
                    false
                }.also { ready -> if (!ready && !process.isAlive) error("UI host stopped: $observation\n${log.readText().takeLast(16_000)}") }
            }
        }
        return api to ui
    }

    fun request(origin: String, path: String, body: String? = null, token: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(15))
        token?.let { builder.header("Authorization", "Bearer $it") }
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun newOrigin(): String = "http://localhost:${freePort()}"

    fun startUi(api: String, origin: String, artifactsEndpoint: String? = null): String {
        val port = URI.create(origin).port
        val config = File(directory, "ui.yaml")
        config.writeText("""
            bosca:
              server:
                port: $port
                drain-timeout-ms: 0
            bml:
              graphqlEndpoint: "$api/graphql"
              artifactsEndpoint: "${artifactsEndpoint.orEmpty()}"
              authCookiePrefix: "_atlas_test"
        """.trimIndent())
        val log = File(directory, "ui.log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "--enable-native-access=ALL-UNNAMED", "-cp", requireNotNull(System.getProperty("atlasServerClasspath")),
            "bosca.atlas.ui.server.ApplicationKt", "-config=${config.absolutePath}",
        ).directory(directory).redirectErrorStream(true).redirectOutput(log).start()
        processes += process
        await("standalone UI ready", log) {
            check(process.isAlive) { "UI host exited: ${log.readText().takeLast(16_000)}" }
            runCatching { request(origin, "/api/v1/ready").statusCode() == 200 }.getOrDefault(false)
        }
        return origin
    }

    fun graphql(origin: String, query: String, token: String? = null): JsonObject {
        val response = request(origin, "/graphql", buildJsonObject { put("query", query) }.toString(), token)
        assertEquals(200, response.statusCode(), response.body())
        val json = Json.parseToJsonElement(response.body()).jsonObject
        assertFalse("errors" in json, json.toString())
        return json.getValue("data").jsonObject
    }

    fun upload(origin: String, path: String, value: String, token: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $token").header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofString(value)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    fun await(description: String, log: File? = null, observation: () -> String = { "" }, assertion: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
        while (System.nanoTime() < deadline) {
            if (assertion()) return
            Thread.sleep(100)
        }
        error("Timed out waiting for $description. ${observation()} ${log?.readText()?.takeLast(16_000) ?: logs()}")
    }

    fun logs(): String = directory.listFiles().orEmpty().filter { it.extension == "log" }
        .joinToString("\n") { "${it.name}:\n${it.readText().takeLast(8_000)}" }

    override fun close() {
        val failed = mutableListOf<String>()
        for (process in processes.asReversed()) {
            process.destroy()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                check(process.waitFor(10, TimeUnit.SECONDS)) { "Atlas process did not exit" }
                failed += "Atlas process did not shut down gracefully"
            }
        }
        search.stop()
        nats.stop()
        postgres.stop()
        assertTrue(failed.isEmpty(), failed.joinToString() + "\n" + logs())
        directory.deleteRecursively()
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }
}
