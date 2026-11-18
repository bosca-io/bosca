package bosca.atlas.server

import io.nats.client.Nats
import io.nats.client.api.KeyValueConfiguration
import kotlinx.serialization.json.*
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Uses an isolated monitoring-enabled NATS server alongside the production Atlas process fixture. */
class AtlasInfrastructureIntegrationTest {
    @Test
    fun `infrastructure consoles read live services and preserve admin authorization`() {
        val broker = GenericContainer<Nothing>(DockerImageName.parse("nats:2.12-alpine")).apply {
            withExposedPorts(4222, 8222)
            withCommand("-js", "-m", "8222")
        }
        broker.start()
        try {
            AtlasProcessFixture().use { fixture ->
                val (api, ui) = fixture.start("infrastructure", mapOf(
                    "ATLAS_RUNNER_ENABLED" to "false", "NATS_HOST" to "${broker.host}:${broker.getMappedPort(4222)}",
                    "NATS_USERNAME" to "", "NATS_PASSWORD" to "", "NATS_TOKEN" to "",
                    "NATS_MONITORING_URL" to "http://${broker.host}:${broker.getMappedPort(8222)}",
                ))
                val standalone = fixture.startUi(api, fixture.newOrigin())
                fun JsonElement.at(vararg keys: String) = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
                val admin = fixture.graphql(api, """mutation { security { login { password(identifier: "admin", password: "atlas-admin-password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
                fixture.graphql(api, """mutation { security { signup { passwordV2(identifier: "infra-reader@example.test", password: "Reader-test-password", profile: {name: "Infrastructure Reader", visibility: PUBLIC, attributes: []}) { principal { id } } } } }""")
                val reader = fixture.graphql(api, """mutation { security { login { password(identifier: "infra-reader@example.test", password: "Reader-test-password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
                for (origin in listOf(ui, standalone)) for (kind in listOf("postgres", "nats", "meilisearch")) {
                    assertEquals(302, fixture.request(origin, "/system/$kind").statusCode())
                    val page = fixture.request(origin, "/system/$kind", token = admin)
                    assertEquals(200, page.statusCode(), fixture.logs())
                    assertTrue("data-bml-state-key=\"system-$kind.model" in page.body(), page.body())
                    assertTrue("Administrator access is required" in fixture.request(origin, "/system/$kind", token = reader).body())
                }
                fun action(kind: String, method: String, args: List<JsonElement>, caller: String = admin, previous: JsonObject? = null): JsonObject {
                    val path = "/system/$kind"
                    val html = fixture.request(ui, path, token = admin).body()
                    val state = Regex("<script type=\"application/json\" data-bml-state-key=\"(system-$kind.model[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html) ?: error(html)
                    val response = fixture.request(ui, "/_bml/action", buildJsonObject {
                        put("page", path); put("path", path); put("stateKey", state.groupValues[1]); put("state", previous?.toString() ?: state.groupValues[2]); put("method", method); put("args", JsonArray(args))
                    }.toString(), caller)
                    assertEquals(200, response.statusCode(), response.body())
                    return Json.parseToJsonElement(Json.parseToJsonElement(response.body()).jsonObject.getValue("state").jsonPrimitive.content).jsonObject
                }
                val stats = action("postgres", "loadTables", listOf(JsonPrimitive("public")))
                assertTrue(stats.at("tables").jsonArray.isNotEmpty(), stats.toString())
                assertTrue(stats.at("feedback").jsonObject["failed"]?.jsonPrimitive?.boolean != true, stats.toString())
                Nats.connect("nats://${broker.host}:${broker.getMappedPort(4222)}").use { connection ->
                    connection.keyValueManagement().create(KeyValueConfiguration.builder().name("system_ui_fixture").build())
                    val bucket = action("nats", "openBucket", listOf(JsonPrimitive("system_ui_fixture")))
                    val args = listOf(JsonPrimitive("sample"), JsonPrimitive("fixture value"), JsonPrimitive(""), JsonPrimitive(""))
                    val denied = action("nats", "putEntry", args, reader, bucket)
                    assertTrue(denied.at("feedback", "failed").jsonPrimitive.boolean)
                    val saved = action("nats", "putEntry", args, previous = bucket)
                    assertTrue(saved.at("feedback").jsonObject["failed"]?.jsonPrimitive?.boolean != true, saved.toString())
                    assertEquals("fixture value", connection.keyValue("system_ui_fixture").get("sample").value?.decodeToString())
                    val prepared = action("nats", "prepareBucketOperation", listOf("deleteKey", "sample", "", "", "", "").map(::JsonPrimitive), previous = saved)
                    val removed = action("nats", "confirmOperation", listOf(JsonPrimitive(true)), previous = prepared)
                    assertTrue(removed.at("feedback").jsonObject["failed"]?.jsonPrimitive?.boolean != true, removed.toString())
                }
                val indexUid = fixture.searchIndex("system-ui-meilisearch")
                val createdIndex = action("meilisearch", "createIndex", listOf(indexUid, "id").map(::JsonPrimitive))
                assertTrue(createdIndex.at("editorCompleted").jsonPrimitive.boolean, createdIndex.toString())
                fixture.await("index creation") {
                    fixture.graphql(api, "{ meilisearchAdmin { indexes { uid } } }", admin).at("meilisearchAdmin", "indexes").jsonArray.any { it.at("uid").jsonPrimitive.content == indexUid }
                }
                val documents = action("meilisearch", "openIndex", listOf(JsonPrimitive(indexUid)))
                assertTrue(documents.at("documentsFeedback", "failed").jsonPrimitive.boolean != true, documents.toString())
                assertEquals(0, documents.at("documents").jsonArray.size)
                val changedSettings = action("meilisearch", "updateSettings", emptyList(), previous = documents)
                assertTrue(changedSettings.at("feedback", "failed").jsonPrimitive.boolean != true, changedSettings.toString())
                fixture.graphql(api, """mutation { meilisearchAdmin { updateEmbedders(uid: "$indexUid", embedders: [{name: "first", source: "userProvided", dimensions: 2}, {name: "second", source: "userProvided", dimensions: 2}]) { uid status } } }""", admin)
                fun embedders() = fixture.graphql(api, """{ meilisearchAdmin { index(uid: "$indexUid") { settings { embedders { name dimensions } } } } }""", admin).at("meilisearchAdmin", "index", "settings", "embedders").jsonArray
                fixture.await("two configured embedders") { embedders().size == 2 }
                var detail = action("meilisearch", "openIndex", listOf(JsonPrimitive(indexUid)))
                var prepared = action("meilisearch", "prepareOperation", listOf("removeEmbedder", indexUid, "first").map(::JsonPrimitive), previous = detail)
                val denied = action("meilisearch", "confirmOperation", listOf(JsonPrimitive(true)), reader, prepared)
                assertTrue(denied.at("feedback", "failed").jsonPrimitive.boolean); assertEquals(2, embedders().size)
                val removed = action("meilisearch", "confirmOperation", listOf(JsonPrimitive(true)), previous = prepared)
                assertTrue(removed.at("operationCompleted").jsonPrimitive.boolean, removed.toString())
                fixture.await("only the selected embedder removed") { embedders().map { it.at("name").jsonPrimitive.content } == listOf("second") }
                detail = action("meilisearch", "openIndex", listOf(JsonPrimitive(indexUid)))
                prepared = action("meilisearch", "prepareOperation", listOf("removeEmbedder", indexUid, "second").map(::JsonPrimitive), previous = detail)
                val reset = action("meilisearch", "confirmOperation", listOf(JsonPrimitive(true)), previous = prepared)
                assertTrue(reset.at("operationCompleted").jsonPrimitive.boolean, reset.toString())
                fixture.await("last embedder reset") { embedders().isEmpty() }
                val preparedIndex = action("meilisearch", "prepareOperation", listOf("deleteIndex", indexUid, "").map(::JsonPrimitive), previous = documents)
                val deletedIndex = action("meilisearch", "confirmOperation", listOf(JsonPrimitive(true)), previous = preparedIndex)
                assertTrue(deletedIndex.at("operationCompleted").jsonPrimitive.boolean, deletedIndex.toString())
                fixture.await("scratch index deletion") {
                    fixture.graphql(api, "{ meilisearchAdmin { indexes { uid } } }", admin).at("meilisearchAdmin", "indexes").jsonArray.none { it.at("uid").jsonPrimitive.content == indexUid }
                }
            }
        } finally { broker.stop() }
    }
}
