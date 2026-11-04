package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.system.ui.generated.SystemNatsComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class NatsActionsTest {
    private val overview get() = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/nats/overview.json")).bufferedReader().use { it.readText() })
    private val message = Json.parseToJsonElement("""{"subject":"fixture.events.json","sequence":9007199254740993,"timestamp":"2026-01-01T00:00:00Z","data":"{\"fixture\":true}","headers":[{"key":"Fixture-Header","values":["first","second"]}]}""")
    private val entry = Json.parseToJsonElement("""{"key":"fixture.key","value":"{\"fixture\":true}","revision":4,"created":"2026-01-01T00:00:00Z","operation":"PUT"}""")
    private val consumer = Json.parseToJsonElement("""{"name":"fixture-consumer","streamName":"fixture_events","created":"2026-01-01T00:00:00Z","delivered":{"consumerSeq":2,"streamSeq":9007199254740993},"ackFloor":{"consumerSeq":1,"streamSeq":9007199254740992},"numAckPending":1,"numRedelivered":2,"numWaiting":3,"numPending":4,"cluster":{"leader":"consumer-leader"}}""")
    private fun result(field: String, value: JsonElement) = buildJsonObject { put("natsAdmin", buildJsonObject { put(field, value) }) }
    private fun context(handler: suspend (String, JsonObject) -> JsonElement = { operation, _ -> when (operation) {
        "SystemGetJetStreamConsumers" -> result("jetStreamConsumers", JsonArray(listOf(consumer)))
        "SystemGetStreamMessages" -> result("streamMessages", JsonArray(listOf(message)))
        "SystemGetKeyValueEntries" -> result("keyValueEntries", JsonArray(listOf(entry)))
        else -> overview
    } }) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private suspend fun model() = NatsConsoleModel(true).also { it.refresh(context()); assertFalse(it.feedback.failed, it.feedback.message) }

    @Test fun `overview refresh preserves filters detail drafts and exact sequences`() = runTest {
        val model = model()
        assertEquals("12d 3h" to "4m 5s", model.uptime)
        model.filterStreams("EVENTS"); assertEquals("fixture_events", model.streams.single().name)
        model.filterBuckets("BUCKET"); assertEquals("fixture_bucket", model.buckets.single().bucket)
        for (filter in listOf("JAVA", "FIXTURE-CLIENT", "127.0.0.1")) { model.filterConnections(filter); assertEquals(1, model.connections.size) }
        model.openStream("fixture_events", context()); assertFalse(model.feedback.failed)
        val request = model.streamOpenRequest
        model.toggleMessage("9007199254740993", "25", "fixture.>", "9007199254740993", "9007199254740995", "fixture")
        model.chooseTab("streams"); model.refresh(context())
        assertEquals("streams", model.tab); assertEquals("EVENTS", model.streamFilter)
        assertEquals("9007199254740993", model.fromSeq); assertEquals("9007199254740993", model.expandedMessage)
        assertNotEquals(request, model.streamOpenRequest); assertEquals(9007199254740993L, model.messages.single().sequence)
    }

    @Test fun `consumer failure does not suppress message diagnostics and validation precedes IO`() = runTest {
        val model = model()
        model.openStream("fixture_events", context { operation, variables ->
            if (operation == "SystemGetJetStreamConsumers") error("Consumer monitoring failed")
            assertEquals(50, variables.getValue("limit").jsonPrimitive.int)
            result("streamMessages", JsonArray(listOf(message)))
        })
        assertFalse(model.feedback.failed); assertTrue(model.consumerFeedback.failed); assertEquals(1, model.messages.size)
        for ((limit, from, to) in listOf(Triple("0", "", ""), Triple("1001", "", ""), Triple("50", "-1", ""), Triple("50", "9223372036854775808", ""), Triple("50", "4", "3"))) {
            model.loadMessages(limit, "", from, to, "", context { _, _ -> error("Unexpected IO") })
            assertTrue(model.messageFeedback.failed); assertNotEquals("Unexpected IO", model.messageFeedback.message)
            assertEquals(limit, model.messageLimit); assertEquals(from, model.fromSeq)
        }
        model.loadMessages("25", "fixture.*", "9007199254740993", "9007199254740995", "fixture", context { operation, variables ->
            assertEquals("SystemGetStreamMessages", operation)
            assertEquals(9007199254740993L, variables.getValue("fromSeq").jsonPrimitive.long)
            assertEquals(9007199254740995L, variables.getValue("toSeq").jsonPrimitive.long)
            assertEquals("fixture.*", variables.getValue("subjectFilter").jsonPrimitive.content)
            result("streamMessages", JsonArray(emptyList()))
        }); assertFalse(model.messageFeedback.failed); assertTrue(model.messages.isEmpty())
    }

    @Test fun `key value filters expansion format and failed writes retain drafts`() = runTest {
        val model = model(); model.openBucket("fixture_bucket", context())
        model.loadEntries("fixture.*", "TRUE", "unsaved.key", "unsaved text", context { _, variables ->
            assertEquals("fixture.*", variables.getValue("keyFilter").jsonPrimitive.content)
            assertEquals("TRUE", variables.getValue("valueSearch").jsonPrimitive.content)
            result("keyValueEntries", JsonArray(listOf(entry)))
        })
        model.toggleEntry("fixture.key", model.draftKey, model.draftValue, model.keyFilter, model.valueSearch)
        model.setValueMode("json", model.draftKey, model.draftValue, model.keyFilter, model.valueSearch)
        model.putEntry(model.draftKey, "{invalid", model.keyFilter, model.valueSearch, context { _, _ -> error("Unexpected IO") })
        assertTrue(model.feedback.failed); assertNotEquals("Unexpected IO", model.feedback.message)
        assertEquals("unsaved.key", model.draftKey); assertEquals("{invalid", model.draftValue); assertEquals("fixture.key", model.expandedKey)
        model.setValueMode("text", model.draftKey, "plain text", model.keyFilter, model.valueSearch)
        model.putEntry(model.draftKey, model.draftValue, model.keyFilter, model.valueSearch, context { _, _ -> error("Write rejected") })
        assertEquals("Write rejected", model.feedback.message); assertEquals("plain text", model.draftValue)
        assertEquals("fixture.*", model.keyFilter); assertEquals("TRUE", model.valueSearch)
    }

    @Test fun `empty text values are written and successful writes clear only writer drafts`() = runTest {
        val model = model(); model.openBucket("fixture_bucket", context())
        model.putEntry("empty", "", "fixture.*", "TRUE", context { operation, variables -> when (operation) {
            "SystemPutKvEntry" -> {
                assertEquals("", variables.getValue("value").jsonPrimitive.content)
                result("putKeyValueEntry", JsonObject(entry.jsonObject + mapOf("key" to JsonPrimitive("empty"), "value" to JsonPrimitive(""))))
            }
            "SystemGetKeyValueEntries" -> result("keyValueEntries", JsonArray(emptyList()))
            else -> overview
        } })
        assertFalse(model.feedback.failed, model.feedback.message); assertEquals("Stored empty.", model.operationNotice)
        assertEquals(1, model.noticeSequence)
        assertEquals("", model.draftKey); assertEquals("", model.draftValue)
        assertEquals("fixture.*", model.keyFilter); assertEquals("TRUE", model.valueSearch)
    }

    @Test fun `all destructive operations need confirmation and enforce mutation results`() = runTest {
        val operations = listOf(
            Triple("deleteMessage", "SystemDeleteStreamMessage", "deleteStreamMessage"),
            Triple("purgeStream", "SystemPurgeStream", "purgeStream"),
            Triple("purgeStreamDeletes", "SystemPurgeStreamDeletes", "purgeStreamDeletes"),
            Triple("deleteKey", "SystemDeleteKvEntry", "deleteKeyValueEntry"),
            Triple("purgeBucketDeletes", "SystemPurgeKvDeletes", "purgeKeyValueDeletes"),
        )
        for ((kind, expectedOperation, field) in operations) {
            val model = model()
            model.operationKind = kind; model.operationResource = "fixture_events"; model.operationTarget = "9007199254740993"
            model.confirmOperation(false, context { _, _ -> error("Unexpected IO") })
            assertEquals("Confirm this operation.", model.feedback.message); assertFalse(model.operationCompleted)
            assertEquals(0, model.noticeSequence)
            if (kind != "purgeStreamDeletes") {
                model.confirmOperation(true, context { operation, _ -> assertEquals(expectedOperation, operation); result(field, JsonPrimitive(false)) })
                assertTrue(model.feedback.failed); assertFalse(model.operationCompleted)
            }
            model.confirmOperation(true, context { operation, variables ->
                if (operation == expectedOperation) {
                    if (kind == "deleteMessage") assertEquals(9007199254740993L, variables.getValue("sequence").jsonPrimitive.long)
                    result(field, if (kind == "purgeStreamDeletes") JsonPrimitive(0) else JsonPrimitive(true))
                } else when (operation) {
                    "SystemGetStreamMessages" -> result("streamMessages", JsonArray(emptyList()))
                    "SystemGetJetStreamConsumers" -> result("jetStreamConsumers", JsonArray(emptyList()))
                    "SystemGetKeyValueEntries" -> result("keyValueEntries", JsonArray(emptyList()))
                    else -> overview
                }
            })
            assertTrue(model.operationCompleted, "$kind: ${model.feedback.message}"); assertFalse(model.feedback.failed, model.feedback.message)
            assertEquals(1, model.noticeSequence)
        }
    }

    @Test fun `completed mutation remains completed when refresh fails and KV sentinel is descriptive`() = runTest {
        val model = model(); model.operationKind = "purgeStreamDeletes"; model.operationResource = "KV_fixture_bucket"
        model.confirmOperation(true, context { operation, _ ->
            if (operation == "SystemPurgeStreamDeletes") result("purgeStreamDeletes", JsonPrimitive(-1)) else error("Refresh unavailable")
        })
        assertTrue(model.operationCompleted); assertTrue(model.feedback.failed); assertEquals("Refresh unavailable", model.feedback.message)
        assertEquals("Key-value tombstones purged from KV_fixture_bucket.", model.operationNotice)
        assertEquals(1, model.noticeSequence)
        assertTrue(model.consumerFeedback.failed); assertTrue(model.messageFeedback.failed)
    }

    @Test fun `authorization replay and cancellation preserve their contracts`() = runTest {
        val reader = NatsConsoleModel(false, operationKind = "purgeStream", operationResource = "fixture_events")
        val unused = context { _, _ -> error("Unexpected IO") }
        reader.refresh(unused); assertEquals("Administrator access is required.", reader.feedback.message)
        reader.putEntry("key", "value", "", "", unused); assertEquals("Administrator access is required.", reader.feedback.message)
        reader.confirmOperation(true, unused); assertEquals("Administrator access is required.", reader.feedback.message)
        val model = model()
        assertFailsWith<CancellationException> { model.refresh(context { _, _ -> throw CancellationException() }) }
        assertFailsWith<CancellationException> { model.openStream("fixture_events", context { _, _ -> throw CancellationException() }) }
    }

    @Test fun `compiled design views expose typed metrics payloads headers consumers and routes`() = runTest {
        val model = model(); model.openStream("fixture_events", context())
        model.toggleMessage("9007199254740993", "50", "", "", "", "")
        suspend fun html(): String { val ctx = RenderContext(); SystemNatsComponent.render(ctx, mapOf("initial" to model)) {}; return ctx.writer.toString() }
        for (text in listOf("12d 3h", "4m 5s", "Reserved storage", "/fixture/store", "Fixture route", "remote-fixture-id", "fixture-consumer", "consumer-leader", "Acknowledgment consumer sequence", "Fixture-Header", "first, second", "9007199254740993", "Message payload", "Purge stream tombstones")) assertTrue(text in html(), text)
        model.openBucket("fixture_bucket", context()); model.toggleEntry("fixture.key", "draft", "retained", "", "")
        for (text in listOf("Unlimited", "1m 5s", "Entry payload", "Purge key-value tombstones", "retained")) assertTrue(text in html(), text)
        assertNull(systemConsoleJson("plain text")); assertNull(systemConsoleJson("true")); assertNull(systemConsoleJson(""))
        assertTrue(systemConsoleJson("[]") is JsonArray); assertTrue(systemConsoleJson("{}") is JsonObject)
    }
}
