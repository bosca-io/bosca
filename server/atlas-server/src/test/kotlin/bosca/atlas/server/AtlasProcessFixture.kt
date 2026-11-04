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
internal class AtlasProcessFixture(
    private val databaseJdbcUrl: String? = null,
    private val databaseUsername: String? = null,
    private val databaseSecret: String? = null,
) : AutoCloseable {
    private val postgres = SharedPostgreSQLContainer().withDatabaseName("atlas_git")
    private val nats = SharedNatsContainer()
    private val search = SharedMeilisearchContainer()
    fun searchIndex(name: String): String = search.indexUid(name)
    val directory = kotlin.io.path.createTempDirectory("atlas-git-").toFile()
    private val processes = mutableListOf<Process>()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val workspace = File(requireNotNull(System.getProperty("workspaceRoot")))

    private val jdbcUrl get() = databaseJdbcUrl ?: postgres.jdbcUrl
    private val username get() = databaseUsername ?: postgres.username
    private val password get() = databaseSecret ?: postgres.password

    init {
        require(listOf(databaseJdbcUrl, databaseUsername, databaseSecret).let { values -> values.all { it == null } || values.all { it != null } })
        if (databaseJdbcUrl == null) postgres.start()
        nats.start()
        search.start()
    }

    fun start(name: String, flags: Map<String, String> = emptyMap(), mainClass: String = "bosca.atlas.server.ApplicationKt", excludedPackages: Set<String> = emptySet()): Pair<String, String> {
        val apiPort = freePort()
        var uiPort = freePort()
        while (apiPort == uiPort) uiPort = freePort()
        val api = "http://localhost:$apiPort"
        val ui = "http://localhost:$uiPort"
        val template = File(workspace, "server/atlas-server/src/main/resources/atlas-server.yaml").readText()
            .lineSequence().filterNot { line -> excludedPackages.any { line == "  - \"$it\"" } }.joinToString("\n")
        val storageType = "    type: \"\$STORAGE_TYPE:s3\""
        require(storageType in template)
        val config = File(directory, "$name.yaml")
        val passkeyOrigins = "    - \"\${WEBAUTHN_EXTRA_ORIGIN:}\""
        config.writeText(template.replace(passkeyOrigins, "    - \"$ui\"\n    - \"${flags["WEBAUTHN_EXTRA_ORIGIN"].orEmpty()}\"").replace(storageType,
            "    type: fs\n    basePath: ${JsonPrimitive(File(directory, "storage").absolutePath)}")
            .replace("cookie-prefixes: []", "cookie-prefixes:\n    - prefix: _atlas_test\n      domains: [localhost]") +
            "\ninitialization:\n  password:\n    admin: atlas-admin-password\n    sa: atlas-sa-password\n")
        val log = File(directory, "$name.log")
        val process = ProcessBuilder(command("ATLAS_SERVER_TEST_BINARY", mainClass, config))
            .directory(directory).apply {
            environment().putAll(mapOf(
                "ATLAS_HTTP_PORT" to apiPort.toString(), "ATLAS_UI_HTTP_PORT" to uiPort.toString(),
                "ATLAS_DEVELOPMENT" to "true", "BML_AUTH_COOKIE_PREFIX" to "_atlas_test",
                "DATABASE_URL" to jdbcUrl, "DATABASE_USER" to username,
                "DATABASE_PASSWORD" to password, "NATS_HOST" to nats.url.removePrefix("nats://"),
                "NATS_USERNAME" to nats.username, "NATS_PASSWORD" to nats.password, "NATS_TOKEN" to "",
                "MEILISEARCH_URL" to search.url, "MEILISEARCH_API_KEY" to search.apiKey,
                "MEILISEARCH_INDEX_PREFIX" to search.namespace, "APP_URL" to ui, "GIT_URL" to api,
                "GIT_UI_URL" to ui, "APP_ALLOWED_ORIGIN" to ui, "OAUTH2_ALLOWED_REDIRECT" to ui,
                "SECURITY_AUTO_VERIFY" to "true", "GRAPHQL_INTROSPECTION_ENABLED" to "true",
                "WELCOME_EMAIL_URL" to "", "ATLAS_RUNNER_CONCURRENCY" to "2",
                "ATLAS_ARTIFACTS_URL" to "",
                "BML_ARTIFACTS_ENDPOINT" to "",
            ) + flags)
            redirectErrorStream(true)
            redirectOutput(log)
        }.start()
        processes += process
        var apiObservation = "No response"
        await("$name ready at $api", log, { apiObservation }) {
            check(process.isAlive) { "Atlas process exited: ${log.readText().takeLast(16_000)}" }
            try {
                val response = request(api, "/api/v1/ready")
                apiObservation = "HTTP ${response.statusCode()}: ${response.body().take(300)}"
                response.statusCode() == 200
            } catch (failure: java.io.IOException) {
                apiObservation = failure.toString()
                false
            }
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

    /** Seeds source content in the disposable database for read-only indexing-preview checks. */
    fun seedSearchMetadata(): String = databaseConnection().use { connection ->
        connection.prepareStatement("insert into metadata(name, content_type, attributes) values (?, ?, ?::jsonb) returning id").use { statement ->
            statement.setString(1, "Search preview fixture")
            statement.setString(2, "application/octet-stream")
            statement.setString(3, "{}")
            statement.executeQuery().use { result -> check(result.next()); result.getString(1) }
        }
    }

    fun searchIndexUid(name: String): String = search.namespace + name

    /** A connection to this fixture only, for initial scratch data and owned diagnostic sessions. */
    fun databaseConnection(): java.sql.Connection = java.sql.DriverManager.getConnection(jdbcUrl, username, password)

    /** Initial disposable organization data for third-party preview and company-sync acceptance. */
    fun seedHubSpotOrganization(): String = databaseConnection().use { connection ->
        connection.prepareStatement("""
            with profile as (insert into profiles(name, type, visibility) values ('HubSpot Company', 'organization', 'public') returning id)
            insert into organizations(name, attributes, system_attributes, profile_id, visibility, created, modified)
            select 'HubSpot Company', '{}'::jsonb, '{}'::jsonb, id, 'public', now(), now() from profile returning profile_id
        """.trimIndent()).use { statement -> statement.executeQuery().use { result -> check(result.next()); result.getString(1) } }
    }

    /** Seeds a binary source in disposable storage so backup tests can inspect archive contents. */
    fun seedBackupFile(content: String): File = databaseConnection().use { connection ->
        connection.prepareStatement("insert into metadata(name, content_type, content_length, uploaded) values (?, 'text/plain', ?, now()) returning id, version").use { statement ->
            statement.setString(1, "Backup file fixture")
            statement.setLong(2, content.toByteArray().size.toLong())
            statement.executeQuery().use { result ->
                check(result.next())
                File(directory, "storage/metadata/${result.getString(1)}/${result.getLong(2)}/content").apply {
                    check(parentFile.mkdirs() || parentFile.isDirectory)
                    writeText(content)
                }
            }
        }
    }

    fun newOrigin(): String = "http://localhost:${freePort()}"

    fun startUi(api: String, origin: String, artifactsEndpoint: String? = null, publicArtifactsOrigin: String = ""): String {
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
        val process = ProcessBuilder(command("ATLAS_UI_TEST_BINARY", "bosca.atlas.ui.server.ApplicationKt", config))
            .directory(directory).apply {
            environment().putAll(mapOf("ATLAS_ARTIFACTS_URL" to publicArtifactsOrigin, "BML_ARTIFACTS_ENDPOINT" to ""))
        }.redirectErrorStream(true).redirectOutput(log).start()
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

    private fun command(binaryVariable: String, mainClass: String, config: File): List<String> {
        val binary = System.getenv(binaryVariable)?.takeIf(String::isNotBlank)?.let(::File)
        val launcher = if (binary == null) {
            listOf(File(System.getProperty("java.home"), "bin/java").absolutePath,
                "--enable-native-access=ALL-UNNAMED", "-cp",
                requireNotNull(System.getProperty(if (mainClass in setOf("bosca.atlas.server.AtlasBibleFixtureKt", "bosca.atlas.server.AtlasHubSpotFixtureKt")) "atlasTestClasspath" else "atlasServerClasspath")), mainClass)
        } else {
            require(binary.isFile && binary.canExecute()) { "Invalid native fixture executable: $binary" }
            val copied = File(directory, binary.name)
            if (!copied.exists()) {
                binary.copyTo(copied)
                check(copied.setExecutable(true)) { "Cannot run native fixture executable: $copied" }
            }
            listOf(copied.absolutePath)
        }
        return launcher + "-config=${config.absolutePath}"
    }

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
        System.getProperty("atlas.native.artifacts")?.let { root ->
            val retained = File(root, directory.name).apply { mkdirs() }
            directory.listFiles().orEmpty().filter { it.extension in setOf("log", "yaml") }.forEach {
                it.copyTo(File(retained, it.name), overwrite = true)
            }
        }
        assertTrue(failed.isEmpty(), failed.joinToString() + "\n" + logs())
        directory.deleteRecursively()
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }
}
