package bosca.atlas.server

import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.KeyValueConfiguration
import io.nats.client.api.StreamConfiguration
import io.nats.client.impl.Headers
import kotlinx.serialization.json.*
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Owned streams and buckets on a monitoring-enabled broker exercise the real administration API. */
class AtlasNatsIntegrationTest {
    @Test fun `NATS diagnostics filters payloads and maintenance work in both Atlas hosts`() {
        GenericContainer<Nothing>(DockerImageName.parse("nats:2.12-alpine")).apply {
            withExposedPorts(4222, 8222); withCommand("-js", "-m", "8222")
        }.use { broker ->
            broker.start()
            Nats.connect(Options.Builder().server("nats://${broker.host}:${broker.getMappedPort(4222)}").connectionName("system-ui-nats-fixture").build()).use { connection ->
                val management = connection.jetStreamManagement()
                val jetstream = connection.jetStream()
                val firstSequence = 9007199254740993L
                fun seed(index: Int) {
                    val name = "system_ui_nats_$index"
                    management.addStream(StreamConfiguration.builder().name(name).subjects("$name.>").firstSequence(firstSequence).build())
                    jetstream.publish("$name.json", Headers().add("Fixture-Header", "first", "second"), """{"fixture":true,"name":"NATS JSON"}""".toByteArray())
                    jetstream.publish("$name.text", "NATS text payload".toByteArray())
                    jetstream.publish("$name.empty", byteArrayOf())
                    jetstream.publish("$name.deleted", Headers().add("KV-Operation", "DEL"), byteArrayOf())
                    management.addOrUpdateConsumer(name, ConsumerConfiguration.builder().durable("fixture_consumer").build())
                    connection.keyValueManagement().create(KeyValueConfiguration.builder().name(name).maxHistoryPerKey(5).maximumValueSize(1024).ttl(Duration.ofHours(1)).build())
                    val kv = connection.keyValue(name)
                    kv.put("fixture.json", """{"fixture":true}"""); kv.put("fixture.text", "NATS text value"); kv.put("fixture.empty", "")
                }
                for (index in 0..3) seed(index)
                management.addStream(StreamConfiguration.builder().name("system_ui_nats_protected").subjects("system_ui_nats_protected.>").denyPurge(true).build())
                AtlasProcessFixture().use { fixture ->
                    val (api, ui) = fixture.start("nats", mapOf(
                        "ATLAS_RUNNER_ENABLED" to "false", "NATS_HOST" to "${broker.host}:${broker.getMappedPort(4222)}",
                        "NATS_USERNAME" to "", "NATS_PASSWORD" to "", "NATS_TOKEN" to "",
                        "NATS_MONITORING_URL" to "http://${broker.host}:${broker.getMappedPort(8222)}",
                    ))
                    val standalone = fixture.startUi(api, fixture.newOrigin())
                    fun JsonElement.at(vararg keys: String) = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
                    fun login(identifier: String, password: String) = fixture.graphql(api, """mutation { security { login { password(identifier:"$identifier",password:"$password") { token { token } } } } }""").at("security", "login", "password", "token", "token").jsonPrimitive.content
                    val admin = login("admin", "atlas-admin-password")
                    fixture.graphql(api, """mutation { security { signup { passwordV2(identifier:"nats-reader@example.test",password:"Reader-test-password",profile:{name:"NATS Reader",visibility:PUBLIC,attributes:[]}) { principal { id } } } } }""")
                    val reader = login("nats-reader@example.test", "Reader-test-password")
                    fun page(origin: String, caller: String = admin) = fixture.request(origin, "/system/nats", token = caller).also { assertEquals(200, it.statusCode(), it.body() + fixture.logs()) }.body()
                    fun invoke(origin: String, method: String, args: List<JsonElement>, caller: String = admin, previous: JsonObject? = null): JsonObject {
                        val marker = Regex("<script type=\"application/json\" data-bml-state-key=\"(system-nats.model[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(page(origin)) ?: error("Missing NATS state")
                        val response = fixture.request(origin, "/_bml/action", buildJsonObject {
                            put("page", "/system/nats"); put("path", "/system/nats"); put("stateKey", marker.groupValues[1]); put("state", previous?.toString() ?: marker.groupValues[2]); put("method", method); put("args", JsonArray(args))
                        }.toString(), caller)
                        assertEquals(200, response.statusCode(), response.body())
                        return Json.parseToJsonElement(Json.parseToJsonElement(response.body()).at("state").jsonPrimitive.content).jsonObject
                    }
                    fun args(vararg values: Any) = values.map { if (it is Boolean) JsonPrimitive(it) else JsonPrimitive(it.toString()) }
                    fun ok(state: JsonObject, feedback: String = "feedback") = assertFalse(state.at(feedback, "failed").jsonPrimitive.boolean, "$state\n${fixture.logs()}")
                    for ((index, origin) in listOf(ui, standalone).withIndex()) {
                        val name = "system_ui_nats_$index"
                        val kv = connection.keyValue(name)
                        assertEquals(302, fixture.request(origin, "/system/nats").statusCode())
                        assertTrue("Administrator access is required" in page(origin, reader))
                        val refreshed = invoke(origin, "refresh", emptyList()); ok(refreshed)
                        assertTrue(refreshed.at("overview", "connections", "connections").jsonArray.any { it.at("name").jsonPrimitive.contentOrNull == "system-ui-nats-fixture" })
                        var stream = invoke(origin, "openStream", args(name)); ok(stream); ok(stream, "consumerFeedback"); ok(stream, "messageFeedback")
                        assertTrue(stream.at("consumers").jsonArray.any { it.at("name").jsonPrimitive.content == "fixture_consumer" })
                        val messages = stream.at("messages").jsonArray
                        assertEquals(firstSequence, messages.first().at("sequence").jsonPrimitive.long)
                        assertEquals(listOf("first", "second"), messages.first().at("headers").jsonArray.single().at("values").jsonArray.map { it.jsonPrimitive.content })
                        assertNull(messages[2].at("data").jsonPrimitive.contentOrNull)
                        stream = invoke(origin, "loadMessages", args("25", "$name.*", firstSequence, firstSequence + 1, "TEXT"), previous = stream); ok(stream, "messageFeedback")
                        assertEquals("$name.text", stream.at("messages").jsonArray.single().at("subject").jsonPrimitive.content)
                        var prepared = invoke(origin, "prepareStreamOperation", args("deleteMessage", firstSequence, "50", "", "", "", ""), previous = stream)
                        assertTrue(invoke(origin, "confirmOperation", args(false), previous = prepared).at("feedback", "failed").jsonPrimitive.boolean)
                        assertTrue(invoke(origin, "confirmOperation", args(true), reader, prepared).at("feedback", "failed").jsonPrimitive.boolean)
                        assertEquals(firstSequence, management.getMessage(name, firstSequence).seq)
                        stream = invoke(origin, "confirmOperation", args(true), previous = prepared); ok(stream)
                        assertTrue(stream.at("operationCompleted").jsonPrimitive.boolean)
                        assertTrue(stream.at("messages").jsonArray.none { it.at("sequence").jsonPrimitive.long == firstSequence })
                        prepared = invoke(origin, "prepareStreamOperation", args("purgeStreamDeletes", "", "50", "", "", "", ""), previous = stream)
                        stream = invoke(origin, "confirmOperation", args(true), previous = prepared); ok(stream)
                        assertTrue(stream.at("operationNotice").jsonPrimitive.content.startsWith("Removed 1 tombstones"))
                        prepared = invoke(origin, "prepareStreamOperation", args("purgeStream", "", "50", "", "", "", ""), previous = stream)
                        ok(invoke(origin, "confirmOperation", args(true), previous = prepared)); assertEquals(0L, management.getStreamInfo(name).streamState.msgCount)
                        var bucket = invoke(origin, "openBucket", args(name)); ok(bucket); ok(bucket, "entryFeedback")
                        bucket = invoke(origin, "loadEntries", args("fixture.*", "TEXT", "unsaved.key", "unsaved text"), previous = bucket); ok(bucket, "entryFeedback")
                        assertEquals("fixture.text", bucket.at("entries").jsonArray.single().at("key").jsonPrimitive.content)
                        assertEquals("unsaved text", bucket.at("draftValue").jsonPrimitive.content)
                        assertTrue(invoke(origin, "putEntry", args("denied", "value", "", ""), reader, bucket).at("feedback", "failed").jsonPrimitive.boolean)
                        assertNull(kv.get("denied"))
                        val failed = invoke(origin, "putEntry", args("invalid key", "retained after rejection", "fixture.*", "TEXT"), previous = bucket)
                        assertTrue(failed.at("feedback", "failed").jsonPrimitive.boolean); assertEquals("retained after rejection", failed.at("draftValue").jsonPrimitive.content)
                        bucket = invoke(origin, "putEntry", args("fixture.written", "", "", ""), previous = failed); ok(bucket)
                        assertEquals(0L, kv.get("fixture.written").dataLen)
                        assertEquals("PUT", kv.get("fixture.written").operation.name)
                        prepared = invoke(origin, "prepareBucketOperation", args("deleteKey", "fixture.written", "draft.key", "draft value", "", ""), previous = bucket)
                        bucket = invoke(origin, "confirmOperation", args(true), previous = prepared); ok(bucket)
                        assertNull(kv.get("fixture.written")); assertEquals("DELETE", kv.history("fixture.written").last().operation.name)
                        assertEquals("draft value", bucket.at("draftValue").jsonPrimitive.content)
                        prepared = invoke(origin, "prepareBucketOperation", args("purgeBucketDeletes", "", "draft.key", "draft value", "", ""), previous = bucket)
                        ok(invoke(origin, "confirmOperation", args(true), previous = prepared))
                        val kvStream = invoke(origin, "openStream", args("KV_$name"))
                        prepared = invoke(origin, "prepareStreamOperation", args("purgeStreamDeletes", "", "50", "", "", "", ""), previous = kvStream)
                        val compacted = invoke(origin, "confirmOperation", args(true), previous = prepared); ok(compacted)
                        assertEquals("Key-value tombstones purged from KV_$name.", compacted.at("operationNotice").jsonPrimitive.content)
                    }
                    if (System.getProperty("atlas.appearance.browser") == "true") {
                        val log = File(fixture.directory, "appearance-browser.log")
                        val browser = ProcessBuilder("node", "src/test/browser/appearance.mjs").directory(File(requireNotNull(System.getProperty("workspaceRoot")), "admin-support/system-ui")).apply {
                            environment().putAll(mapOf("ATLAS_SYSTEM_TEST_URL" to ui, "ATLAS_APPEARANCE_STANDALONE_URL" to standalone, "ATLAS_SYSTEM_TEST_PASSWORD" to "atlas-admin-password"))
                        }.redirectErrorStream(true).redirectOutput(log).start()
                        try { assertTrue(browser.waitFor(300, TimeUnit.SECONDS), "System appearance browser timed out"); assertEquals(0, browser.exitValue(), log.readText() + fixture.logs()) }
                        finally { if (browser.isAlive) { browser.destroy(); if (!browser.waitFor(5, TimeUnit.SECONDS)) browser.destroyForcibly() } }
                    }
                    if (System.getProperty("atlas.nats.browser") == "true") {
                        val log = File(fixture.directory, "nats-browser.log")
                        val browser = ProcessBuilder("node", "src/test/browser/nats.mjs").directory(File(requireNotNull(System.getProperty("workspaceRoot")), "admin-support/system-ui")).apply {
                            environment().putAll(mapOf("ATLAS_SYSTEM_TEST_URL" to ui, "ATLAS_NATS_STANDALONE_URL" to standalone, "ATLAS_SYSTEM_TEST_PASSWORD" to "atlas-admin-password"))
                        }.redirectErrorStream(true).redirectOutput(log).start()
                        try { assertTrue(browser.waitFor(240, TimeUnit.SECONDS), "NATS browser timed out"); assertEquals(0, browser.exitValue(), log.readText() + fixture.logs()) }
                        finally { if (browser.isAlive) { browser.destroy(); if (!browser.waitFor(5, TimeUnit.SECONDS)) browser.destroyForcibly() } }
                        for (index in 2..3) {
                            assertEquals(0L, management.getStreamInfo("system_ui_nats_$index").streamState.msgCount)
                            assertEquals(0L, connection.keyValue("system_ui_nats_$index").get("browser.empty").dataLen)
                            assertEquals("PUT", connection.keyValue("system_ui_nats_$index").get("browser.empty").operation.name)
                        }
                    }
                }
            }
        }
    }
}
