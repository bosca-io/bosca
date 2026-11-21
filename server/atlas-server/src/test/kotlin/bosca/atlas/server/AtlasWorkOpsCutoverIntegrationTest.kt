package bosca.atlas.server

import bosca.test.resources.SharedValkeyContainer
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rehearses a UI routing switch while the actual API, storage and worker stay running. */
class AtlasWorkOpsCutoverIntegrationTest {
    @Test
    fun `saved WorkOps documents survive Studio Atlas and restored Studio routing`() {
        assumeTrue("Enable the isolated Studio cutover rehearsal explicitly", java.lang.Boolean.getBoolean("atlas.workops.cutover"))
        val workspace = File(requireNotNull(System.getProperty("workspaceRoot")))
        AtlasProcessFixture(imageRenditions = false).use { fixture ->
            val reports = File(workspace, "server/atlas-server/build/reports/workops-cutover/${fixture.directory.name}").apply { mkdirs() }
            val boot = File(fixture.directory, "Caddyfile").apply { writeText("{\n auto_https off\n}\n:80 {\n respond 503\n}\n") }
            GenericContainer("caddy:2.11.4").apply {
                withExposedPorts(80)
                withAccessToHost(true)
                withCopyFileToContainer(MountableFile.forHostPath(boot.toPath()), "/etc/caddy/Caddyfile")
                waitingFor(Wait.forListeningPort())
            }.use { proxy ->
                proxy.start()
                val origin = "http://localhost:${proxy.getMappedPort(80)}"
                val platform = fixture.start("platform", mapOf("ATLAS_UI_ENABLED" to "false",
                    "APP_URL" to origin, "APP_ALLOWED_ORIGIN" to origin, "WEBAUTHN_EXTRA_ORIGIN" to origin,
                    "GIT_SYNC_LISTENERS" to "false", "SCHEDULER_ENABLED" to "false"), mainClass = "bosca.server.ApplicationKt").first
                val (api, _) = fixture.start("cutover", mapOf("ATLAS_UI_ENABLED" to "false",
                    "APP_URL" to origin, "APP_ALLOWED_ORIGIN" to origin, "WEBAUTHN_EXTRA_ORIGIN" to origin))
                val atlas = fixture.startUi(api, fixture.newOrigin())
                val token = fixture.graphql(api, """mutation { security { login { password(identifier:"admin",password:"atlas-admin-password") { token { token } } } } }""")
                    .at("security", "login", "password", "token", "token").jsonPrimitive.content
                fun query(source: String) = fixture.graphql(api, source, token)
                query("""mutation {workOps{notifications{updatePreferences(input:{eventChannels:{TASK_CREATED:["IN_APP"],SPEC_CREATED:["IN_APP"],SPEC_UPDATED:["IN_APP"]},watchAuthored:true,watchCommented:true,dailyDigest:false,mutedTaskIds:[],mutedProjectIds:[]}){version}}}}""")
                val profile = query("{profiles{current{id}}}").at("profiles", "current").jsonArray.first().at("id").jsonPrimitive.content
                val portfolio = query("""mutation {workOps{portfolios{create(input:{key:"CUT",name:"Cutover portfolio",ownerProfileId:"$profile"}){id}}}}""")
                    .at("workOps", "portfolios", "create", "id").jsonPrimitive.content
                val program = query("""mutation {workOps{programs{create(input:{portfolioId:"$portfolio",key:"CUT",name:"Cutover program",ownerProfileId:"$profile"}){id}}}}""")
                    .at("workOps", "programs", "create", "id").jsonPrimitive.content
                val project = query("""mutation {workOps{projects{create(input:{programId:"$program",key:"CUT",name:"Cutover project",ownerProfileId:"$profile"}){id}}}}""")
                    .at("workOps", "projects", "create", "id").jsonPrimitive.content
                val spec = query("""mutation {workOps{specs{create(input:{projectId:"$project",name:"Cutover document"}){id key metadataId}}}}""")
                    .at("workOps", "specs", "create")
                val metadata = spec.at("metadataId").jsonPrimitive.content
                assertTrue(query("""mutation {content{metadata{setMetadataDocument(id:"$metadata",version:1,document:{title:"Cutover document",content:{document:{type:"doc",content:[{type:"heading",attrs:{level:1},content:[{type:"text",text:"Cutover document"}]},{type:"paragraph",content:[{type:"text",text:"Saved before switching UI routing."}]}]}}})}}}""")
                    .at("content", "metadata", "setMetadataDocument").jsonPrimitive.boolean)
                val task = query("""mutation {workOps{tasks{create(input:{projectId:"$project",summary:"Cutover background task"}){id}}}}""")
                    .at("workOps", "tasks", "create", "id").jsonPrimitive.content
                val redis = SharedValkeyContainer().apply { start() }
                try {
                    val studioOrigin = fixture.newOrigin()
                    val studioEnvironment = mapOf("API_URL" to platform, "NUXT_API_URL" to platform,
                        "COLLABORATION_URL" to platform, "NUXT_PUBLIC_AUTH_COOKIE_PREFIX" to "_atlas_test",
                        "NUXT_PUBLIC_AUTH_DOMAIN" to "localhost", "REDIS_HOST" to redis.host,
                        "REDIS_PORT" to redis.firstMappedPort.toString(), "REDIS_PREFIX" to redis.namespace)
                    run(ProcessBuilder("pnpm", "--filter", "@bosca/studio", "build").directory(File(workspace, "web"))
                        .apply { environment().putAll(studioEnvironment) }, File(reports, "studio-build.log"), 900)
                    val packaged = File(fixture.directory, "studio")
                    assertTrue(File(workspace, "web/projects/studio/.output").copyRecursively(packaged))
                    fun manifest() = packaged.walkTopDown().filter(File::isFile).associate {
                        it.relativeTo(packaged).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256")
                            .digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
                    }
                    val before = manifest()
                    File(reports, "studio-manifest.json").writeText(Json.encodeToString(before))
                    val studio = ProcessBuilder("node", File(packaged, "server/index.mjs").absolutePath)
                        .directory(packaged).apply { environment().putAll(studioEnvironment + mapOf(
                            "HOST" to "0.0.0.0", "PORT" to URI(studioOrigin).port.toString(), "NODE_ENV" to "production")) }
                        .redirectErrorStream(true).redirectOutput(File(reports, "studio.log")).start()
                    try {
                        val studioHttp = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(Duration.ofSeconds(2)).build()
                        var observation = "No response"
                        fixture.await("packaged Studio ready", File(reports, "studio.log"), { observation }) {
                            check(studio.isAlive) { File(reports, "studio.log").readText().takeLast(16000) }
                            try {
                                val response = studioHttp.send(HttpRequest.newBuilder(URI("$studioOrigin/auth/login"))
                                    .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
                                observation = "HTTP ${response.statusCode()}: ${response.body().take(150)}"
                                response.statusCode() == 200
                            } catch (failure: java.io.IOException) { observation = failure.toString(); false }
                        }
                        Testcontainers.exposeHostPorts(URI(api).port, URI(atlas).port, URI(studioOrigin).port)
                        fun target(value: String) = "host.testcontainers.internal:${URI(value).port}"
                        val source = File(workspace, "deployments/local/Caddyfile").readText()
                        val atlasBlock = source.substringAfter(":8094 {\n").substringBefore("\n}")
                            .replace("atlas-server:8094", target(api)).replace("atlas-server:8095", target(atlas))
                        assertTrue("atlasUi" in atlasBlock && "/workops" in atlasBlock && "atlas-server:" !in atlasBlock, atlasBlock)
                        val configurations = mapOf("studio" to "{\n auto_https off\n}\n:80 {\n reverse_proxy ${target(studioOrigin)}\n}\n",
                            "atlas" to "{\n auto_https off\n}\n:80 {\n$atlasBlock\n}\n")
                        configurations.forEach { (name, content) ->
                            val file = File(reports, "$name.Caddyfile").apply { writeText(content) }
                            proxy.copyFileToContainer(MountableFile.forHostPath(file.toPath()), "/etc/caddy/$name.Caddyfile")
                        }
                        val switches = mutableListOf<String>()
                        fun switch(name: String) {
                            require(name in configurations)
                            val result = proxy.execInContainer("caddy", "reload", "--config", "/etc/caddy/$name.Caddyfile", "--adapter", "caddyfile")
                            assertEquals(0, result.exitCode, result.stdout + result.stderr)
                            switches += name
                        }
                        switch("studio")
                        val executor = Executors.newVirtualThreadPerTaskExecutor()
                        val control = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                            this.executor = executor
                            createContext("/switch") { exchange -> exchange.use {
                                check(exchange.requestMethod == "POST")
                                switch(exchange.requestBody.readAllBytes().decodeToString())
                                exchange.sendResponseHeaders(204, -1)
                            } }
                            start()
                        }
                        try {
                            run(ProcessBuilder("node", "src/test/browser/cutover.mjs").directory(File(workspace, "workops/workops-ui"))
                                .apply { environment().putAll(mapOf("ATLAS_CUTOVER_URL" to origin,
                                    "ATLAS_CUTOVER_CONTROL" to "http://127.0.0.1:${control.address.port}",
                                    "ATLAS_CUTOVER_SPEC" to spec.at("key").jsonPrimitive.content,
                                    "ATLAS_CUTOVER_PROJECT" to project,
                                    "ATLAS_CUTOVER_METADATA" to metadata, "ATLAS_CUTOVER_REPORTS" to reports.absolutePath,
                                    "ATLAS_WORKOPS_TEST_PASSWORD" to "atlas-admin-password")) }, File(reports, "browser.log"), 300)
                        } finally { control.stop(0); executor.close() }
                        assertEquals(listOf("studio", "atlas", "studio"), switches)
                        val stored = query("""{content{metadata(id:"$metadata",version:1){id document{title content}}}}""")
                        assertEquals(metadata, stored.at("content", "metadata", "id").jsonPrimitive.content)
                        assertTrue("Saved through Atlas after routing switch." in stored.toString(), stored.toString())
                        val tasks = Json.parseToJsonElement(File(reports, "browser-evidence.json").readText()).jsonArray
                            .map { it.at("taskId").jsonPrimitive.content } + task
                        fixture.await("indexing and notification completion across routing changes") {
                            val events = query("{scheduler{history(source:EVENT,offset:0,limit:200){name status definition errorMessage}}}")
                                .at("scheduler", "history").jsonArray.filter { it.at("name").jsonPrimitive.content == "task-index" &&
                                    (it.at("definition") as? JsonObject)?.get("taskId")?.jsonPrimitive?.contentOrNull in tasks }
                            assertTrue(events.none { it.at("status").jsonPrimitive.content == "FAILED" }, events.toString())
                            val notified = query("{workOps{notifications{mine(offset:0,limit:100){taskId}}}}")
                                .at("workOps", "notifications", "mine").jsonArray.map { it.at("taskId").jsonPrimitive.contentOrNull }
                            tasks.all { id -> id in notified && events.any { it.at("status").jsonPrimitive.content == "COMPLETED" &&
                                it.at("definition", "taskId").jsonPrimitive.content == id } }
                        }
                        File(reports, "readback.json").writeText(stored.toString())
                    } finally {
                        studio.destroy()
                        if (!studio.waitFor(20, TimeUnit.SECONDS)) { studio.destroyForcibly(); check(studio.waitFor(10, TimeUnit.SECONDS)) }
                        assertEquals(before, manifest(), "The staged Studio package changed during the rehearsal")
                    }
                } finally { redis.stop() }
                fixture.directory.listFiles().orEmpty().filter { it.extension in setOf("log", "yaml") }
                    .forEach { it.copyTo(File(reports, it.name), overwrite = true) }
            }
        }
    }

    private fun run(builder: ProcessBuilder, log: File, seconds: Long) {
        val process = builder.redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertTrue(process.waitFor(seconds, TimeUnit.SECONDS), "Timed out: $log")
            assertEquals(0, process.exitValue(), log.readText().takeLast(16000))
        } finally {
            if (process.isAlive) { process.destroyForcibly(); check(process.waitFor(10, TimeUnit.SECONDS)) }
        }
    }

    private fun JsonElement.at(vararg names: String): JsonElement = names.fold(this) { value, name -> value.jsonObject.getValue(name) }
}
