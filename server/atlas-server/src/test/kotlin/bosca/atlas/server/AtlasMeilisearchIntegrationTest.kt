package bosca.atlas.server

import bosca.meilisearch.client.MeilisearchClient
import bosca.meilisearch.client.model.EmbedderConfig
import bosca.meilisearch.client.model.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Dedicated search and owned embedding endpoints exercise asynchronous mutations in both hosts. */
class AtlasMeilisearchIntegrationTest {
    @Test fun `Meilisearch resources forms and asynchronous operations work in both Atlas hosts`() = runBlocking {
        lateinit var client: MeilisearchClient
        val serial = AtomicInteger()
        val indexUids = (0..3).map { "system_ui_meili_$it" }
        AtlasMeilisearchProvider { host -> runBlocking {
            fun document() = buildJsonArray { add(buildJsonObject { put("id", "held-${serial.incrementAndGet()}"); put("title", "Held indexing fixture"); put("_vectors", buildJsonObject {
                for (name in listOf("first", "second", "third")) put(name, JsonArray(listOf(JsonPrimitive(0.1), JsonPrimitive(0.2))))
            }) }) }.toString()
            client.addDocuments(indexUids[host + 2], document()).taskUid to client.addDocuments(indexUids[host + 2], document()).taskUid
        } }.use { provider ->
            Testcontainers.exposeHostPorts(provider.port)
            GenericContainer<Nothing>(DockerImageName.parse("getmeili/meilisearch:v1.42.0")).apply {
                withExposedPorts(7700); withEnv("MEILI_MASTER_KEY", "system-ui-meilisearch-key"); withEnv("MEILI_ENV", "development"); withEnv("MEILI_NO_ANALYTICS", "true")
                withEnv("MEILI_EXPERIMENTAL_ALLOWED_IP_NETWORKS", "any")
            }.use { search ->
                search.start()
                val url = "http://${search.host}:${search.getMappedPort(7700)}"
                client = MeilisearchClient(url, "system-ui-meilisearch-key", Json { ignoreUnknownKeys = true; explicitNulls = false })
                client.use {
                    suspend fun wait(uid: Int) {
                        repeat(300) {
                            val task = client.getTask(uid)
                            if (task.status == "succeeded") return
                            check(task.status !in listOf("failed", "canceled")) { "Task #$uid: $task" }
                            delay(100)
                        }
                        error("Task #$uid did not finish")
                    }
                    for (uid in indexUids) {
                        wait(client.createIndex(uid, "id").taskUid)
                        wait(client.updateSettings(uid, Settings(searchableAttributes = listOf("title"), filterableAttributes = listOf("category"), sortableAttributes = listOf("title"), stopWords = listOf("the"), synonyms = mapOf("phone" to listOf("mobile")))).taskUid)
                        val documents = buildJsonArray { for (index in 1..25) add(buildJsonObject {
                            put("id", "document-$index"); put("title", "Meilisearch fixture $index"); put("category", "fixture")
                            put("_vectors", buildJsonObject { for (name in listOf("first", "second", "third")) put(name, JsonArray(listOf(JsonPrimitive(0.1), JsonPrimitive(0.2)))) })
                        }) }
                        wait(client.addDocuments(uid, documents.toString()).taskUid)
                        wait(client.updateEmbedders(uid, mapOf("first" to EmbedderConfig("userProvided", dimensions = 2), "second" to EmbedderConfig("userProvided", dimensions = 2))).taskUid)
                    }
                    AtlasProcessFixture().use { fixture ->
                        val (api, ui) = fixture.start("meilisearch", mapOf("ATLAS_RUNNER_ENABLED" to "false", "MEILISEARCH_URL" to url, "MEILISEARCH_API_KEY" to "system-ui-meilisearch-key", "MEILISEARCH_INDEX_PREFIX" to "system_meilisearch_fixture"))
                        val standalone = fixture.startUi(api, fixture.newOrigin())
                        fun JsonElement.at(vararg keys: String) = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
                        fun login(identifier: String, password: String) = fixture.graphql(api, """mutation { security { login { password(identifier:"$identifier",password:"$password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
                        val admin = login("admin", "atlas-admin-password")
                        fixture.graphql(api, """mutation { security { signup { passwordV2(identifier:"meili-reader@example.test",password:"Reader-test-password",profile:{name:"Meilisearch Reader",visibility:PUBLIC,attributes:[]}) { principal { id } } } } }""")
                        val reader = login("meili-reader@example.test", "Reader-test-password")
                        fun page(origin: String, caller: String = admin) = fixture.request(origin, "/system/meilisearch", token = caller).also { assertEquals(200, it.statusCode(), it.body() + fixture.logs()) }.body()
                        fun invoke(origin: String, method: String, args: List<JsonElement>, caller: String = admin, previous: JsonObject? = null): JsonObject {
                            val marker = Regex("<script type=\"application/json\" data-bml-state-key=\"(system-meilisearch.model[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(page(origin)) ?: error("Missing Meilisearch state")
                            val response = fixture.request(origin, "/_bml/action", buildJsonObject {
                                put("page", "/system/meilisearch"); put("path", "/system/meilisearch"); put("stateKey", marker.groupValues[1]); put("state", previous?.toString() ?: marker.groupValues[2]); put("method", method); put("args", JsonArray(args))
                            }.toString(), caller)
                            assertEquals(200, response.statusCode(), response.body())
                            return Json.parseToJsonElement(Json.parseToJsonElement(response.body()).at("state").jsonPrimitive.content).jsonObject
                        }
                        fun args(vararg values: String) = values.map(::JsonPrimitive)
                        fun ok(value: JsonObject, name: String = "feedback") = assertFalse(value.at(name, "failed").jsonPrimitive.boolean, value.toString() + fixture.logs())
                        for ((host, origin) in listOf(ui, standalone).withIndex()) {
                            val uid = indexUids[host]
                            assertEquals(302, fixture.request(origin, "/system/meilisearch").statusCode())
                            assertTrue("Administrator access is required" in page(origin, reader))
                            var detail = invoke(origin, "openIndex", args(uid)); ok(detail); ok(detail, "settingsFeedback"); ok(detail, "documentsFeedback"); ok(detail, "embedderFeedback")
                            assertEquals(20, detail.at("documents").jsonArray.size); assertEquals(25, detail.at("documentsTotal").jsonPrimitive.int)
                            detail = invoke(origin, "loadDocuments", listOf(JsonPrimitive(20)), previous = detail); ok(detail, "documentsFeedback"); assertEquals(5, detail.at("documents").jsonArray.size)
                            var operation = invoke(origin, "prepareOperation", args("removeEmbedder", uid, "first"), previous = detail)
                            val denied = invoke(origin, "confirmOperation", listOf(JsonPrimitive(true)), reader, operation); assertTrue(denied.at("feedback", "failed").jsonPrimitive.boolean)
                            val removed = invoke(origin, "confirmOperation", listOf(JsonPrimitive(true)), previous = operation); ok(removed); assertTrue(removed.at("operationCompleted").jsonPrimitive.boolean)
                            fixture.await("selected embedder removal") { runBlocking { client.getEmbedders(uid)?.keys == setOf("second") } }
                            detail = invoke(origin, "openIndex", args(uid))
                            operation = invoke(origin, "prepareOperation", args("clearDocuments", uid, ""), previous = detail)
                            ok(invoke(origin, "confirmOperation", listOf(JsonPrimitive(true)), previous = operation))
                            fixture.await("document deletion") { runBlocking { client.getDocuments(uid).total == 0 } }
                        }
                        if (System.getProperty("atlas.meilisearch.browser") == "true") {
                            val log = File(fixture.directory, "meilisearch-browser.log")
                            val browser = ProcessBuilder("node", "src/test/browser/meilisearch.mjs").directory(File(requireNotNull(System.getProperty("workspaceRoot")), "admin-support/system-ui")).apply {
                                environment().putAll(mapOf("ATLAS_SYSTEM_TEST_URL" to ui, "ATLAS_MEILI_STANDALONE_URL" to standalone, "ATLAS_SYSTEM_TEST_PASSWORD" to "atlas-admin-password", "ATLAS_MEILI_URL" to url, "ATLAS_MEILI_KEY" to "system-ui-meilisearch-key", "ATLAS_MEILI_PROVIDER" to provider.origin, "ATLAS_MEILI_PROVIDER_REMOTE_0" to provider.remoteUrl(0), "ATLAS_MEILI_PROVIDER_REMOTE_1" to provider.remoteUrl(1)))
                            }.redirectErrorStream(true).redirectOutput(log).start()
                            try { assertTrue(browser.waitFor(300, TimeUnit.SECONDS), "Meilisearch browser timed out"); assertEquals(0, browser.exitValue(), log.readText() + "\nEmbedding provider requests: ${provider.credentials.size}\n" + search.logs.takeLast(8_000) + fixture.logs()) }
                            finally { if (browser.isAlive) { browser.destroy(); if (!browser.waitFor(5, TimeUnit.SECONDS)) browser.destroyForcibly() } }
                            for (uid in indexUids.takeLast(2)) {
                                assertEquals(0, client.getDocuments(uid).total)
                                assertTrue(client.getEmbedders(uid).isNullOrEmpty())
                            }
                            for (uid in provider.canceledTasks) assertEquals("canceled", client.getTask(uid).status)
                            assertTrue(provider.credentials.isNotEmpty()); assertTrue(provider.credentials.all { it == "Bearer fixture-embedder-key" })
                        }
                    }
                }
            }
        }
    }
}
