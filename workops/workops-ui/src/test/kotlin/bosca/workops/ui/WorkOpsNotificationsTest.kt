package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsNotificationsTest {
    private val json = Json { encodeDefaults = true }
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: buildJsonObject {})
    })
    private fun data(field: String, value: JsonElement) = buildJsonObject { putJsonObject("workOps") { putJsonObject("notifications") { put(field, value) } } }
    private val recipients = buildJsonObject {
        putJsonArray("TASK_CREATED") { add(buildJsonObject { put("type", "Assignee") }); add(buildJsonObject { put("type", "Group"); put("groupId", "stored-group") }) }
        putJsonArray("LEGACY_EVENT") { add(buildJsonObject { put("type", "LegacyRecipient"); put("kept", true) }) }
    }
    private val scheme = WorkOpsNotificationSchemeRecord("scheme", "Stored scheme", "Stored notes", recipients)
    private fun schemes() = data("schemes", buildJsonArray { add(buildJsonObject { put("id", "scheme"); put("name", "Stored scheme"); put("description", "Stored notes"); put("eventRecipients", recipients) }) })
    private val draft = WorkOpsNotificationDraft(watchAuthored = false, dailyDigest = true, dndStartLocal = "22:00", dndEndLocal = "08:00", eventChannels = buildJsonObject { putJsonArray("TASK_CREATED") {}; putJsonArray("LEGACY_EVENT") { add("EMAIL") } }, mutedTaskIds = listOf("stored-task"), mutedProjectIds = listOf("stored-project")).withFlags()
    private fun attributes(draft: WorkOpsNotificationDraft) = json.encodeToString(WorkOpsNotificationDraft.serializer(), draft)
    private fun preferenceResponse(draft: WorkOpsNotificationDraft) = buildJsonObject { putJsonObject("workOps") { putJsonObject("notifications") {
        put("unreadCount", 5)
        putJsonObject("myPreferences") {
            put("eventChannels", draft.eventChannels); put("watchAuthored", draft.watchAuthored); put("watchCommented", draft.watchCommented); put("dailyDigest", draft.dailyDigest)
            put("dndStartLocal", draft.dndStartLocal); put("dndEndLocal", draft.dndEndLocal); put("mutedTaskIds", JsonArray(draft.mutedTaskIds.map(::JsonPrimitive))); put("mutedProjectIds", JsonArray(draft.mutedProjectIds.map(::JsonPrimitive))); put("version", 4)
        }
    } } }

    @Test fun `scheme selection defaults only when unset and retains an explicit unavailable selection`() = runTest {
        val first = WorkOpsNotificationsModel(); first.retry(context { operation, _ -> assertEquals("WorkOpsNotificationSchemes", operation); schemes() })
        assertTrue(first.loaded); assertEquals("scheme", first.schemeId); assertEquals(recipients, first.selected?.recipients)
        val missing = WorkOpsNotificationsModel(schemeId = "unavailable"); missing.retry(context { _, _ -> schemes() })
        assertTrue(missing.loaded); assertEquals("unavailable", missing.schemeId); assertNull(missing.selected)
    }

    @Test fun `read failures remain distinct from empty schemes and do not affect a preference draft`() = runTest {
        val prefs = WorkOpsInboxModel(tab = "preferences", preferencesLoaded = true, draft = draft)
        val model = WorkOpsNotificationsModel(schemeId = "scheme", schemes = listOf(scheme), loaded = true, preferences = prefs)
        model.retry(context { _, _ -> error("Scheme read unavailable") }); assertFalse(model.loaded); assertTrue(model.feedback.failed); assertEquals("scheme", model.schemeId)
        assertSame(prefs, model.preferences); assertEquals(draft, prefs.draft); assertTrue(prefs.preferencesLoaded)
        model.retry(context { _, _ -> data("schemes", JsonArray(emptyList())) }); assertTrue(model.loaded); assertTrue(model.schemes.isEmpty()); assertFalse(model.feedback.failed)
    }

    @Test fun `event presentation retains unknown event and recipient names without mutating opaque scheme parameters`() {
        val events = scheme.events
        assertEquals(listOf("Assignee", "Group"), events.single { it.key == "TASK_CREATED" }.recipients)
        assertEquals(listOf("LegacyRecipient"), events.single { it.key == "LEGACY_EVENT" }.recipients)
        assertEquals("Legacy event", events.single { it.key == "LEGACY_EVENT" }.label)
        assertTrue(events.single { it.key == "TASK_UPDATED" }.recipients.isEmpty())
        assertEquals(JsonPrimitive("stored-group"), scheme.recipients.jsonObject.getValue("TASK_CREATED").jsonArray[1].jsonObject["groupId"])
        assertEquals(recipients, scheme.recipients)
    }

    @Test fun `malformed stored recipients are unavailable rather than silently reported as no recipients`() {
        assertFalse(scheme.copy(recipients = JsonArray(emptyList())).available)
        val malformed = scheme.copy(recipients = buildJsonObject { put("TASK_CREATED", JsonNull); putJsonArray("TASK_UPDATED") { add(JsonPrimitive("bad")); add(buildJsonObject { put("type", "") }) } })
        assertFalse(malformed.events.single { it.key == "TASK_CREATED" }.available)
        assertEquals(listOf("Recipient unavailable", "Recipient unavailable"), malformed.events.single { it.key == "TASK_UPDATED" }.recipients)
        assertTrue(malformed.events.single { it.key == "TASK_RESOLVED" }.available)
    }

    @Test fun `event offset and tab links retain the selected scheme`() {
        val model = WorkOpsNotificationsModel(schemeId = "scheme", offset = 25, schemes = listOf(scheme), loaded = true)
        assertEquals(scheme.events.drop(25).take(25), model.pageEvents)
        assertEquals("/workops/settings/notifications?tab=schemes&schemeId=scheme&offset=0", model.previousHref)
        assertTrue(model.tabs.all { "schemeId=scheme" in it.href }); assertEquals(1, model.tabs.count { it.selected })
    }

    @Test fun `unavailable scheme selection has no events or forward page and retains its return link`() {
        val model = WorkOpsNotificationsModel(schemeId = "missing", offset = 25, schemes = listOf(scheme), loaded = true)
        assertNull(model.selected); assertTrue(model.pageEvents.isEmpty()); assertEquals("", model.nextHref)
        assertEquals("/workops/settings/notifications?tab=schemes&schemeId=missing&offset=0", model.previousHref)
        val first = WorkOpsNotificationsModel(schemeId = "missing", loaded = true)
        assertTrue(first.pageEvents.isEmpty()); assertEquals("", first.previousHref); assertEquals("", first.nextHref)
    }

    @Test fun `preference switches distinguish missing defaults from opt outs and malformed stored channels`() {
        val channels = buildJsonObject {
            put("TASK_CREATED", JsonNull)
            put("TASK_UPDATED", JsonPrimitive("EMAIL"))
            putJsonArray("TASK_ASSIGNED") { add(JsonNull); add(buildJsonObject { put("channel", "EMAIL") }); add("IN_APP") }
            putJsonArray("TASK_RESOLVED") {}
        }
        val stored = WorkOpsNotificationDraft(eventChannels = channels)
        assertEquals(setOf("IN_APP", "EMAIL"), stored.channelsFor("MISSING"))
        assertEquals(setOf("IN_APP", "EMAIL"), stored.channelsFor("TASK_CREATED"))
        assertEquals(setOf("IN_APP", "EMAIL"), stored.channelsFor("TASK_UPDATED"))
        assertEquals(setOf("IN_APP"), stored.channelsFor("TASK_ASSIGNED"))
        assertTrue(stored.channelsFor("TASK_RESOLVED").isEmpty())
        val displayed = stored.withFlags()
        assertEquals(false, displayed.flags.getValue("TASK_ASSIGNED")["EMAIL"])
        assertTrue(displayed.flags.getValue("TASK_RESOLVED").values.none { it })
        assertEquals(channels, displayed.updatedChannels())
        val partial = stored.copy(flags = mapOf("TASK_ASSIGNED" to mapOf("EMAIL" to true)))
        assertEquals(setOf("IN_APP", "EMAIL"), partial.copy(eventChannels = partial.updatedChannels()).channelsFor("TASK_ASSIGNED"))
        assertEquals(JsonNull, partial.updatedChannels()["TASK_CREATED"])
    }

    @Test fun `preference saves delegate the existing caller-owned payload and keep independent scheme data`() = runTest {
        val prefs = WorkOpsInboxModel(tab = "preferences", preferencesLoaded = true)
        val model = WorkOpsNotificationsModel(tab = "preferences", schemeId = "scheme", schemes = listOf(scheme), loaded = true, preferences = prefs)
        model.savePreferences(attributes(draft), context { operation, variables ->
            when (operation) {
                "WorkOpsSaveNotificationPreferences" -> {
                    val input = variables.getValue("input").jsonObject
                    assertFalse(input.containsKey("profileId")); assertEquals(draft.eventChannels, input.getValue("eventChannels")); assertEquals(JsonPrimitive(false), input["watchAuthored"]); assertEquals(JsonPrimitive(true), input["dailyDigest"])
                    assertEquals("22:00", input.getValue("dndStartLocal").jsonPrimitive.content); assertEquals("08:00", input.getValue("dndEndLocal").jsonPrimitive.content)
                    assertEquals(JsonArray(listOf(JsonPrimitive("stored-task"))), input["mutedTaskIds"]); assertEquals(JsonArray(listOf(JsonPrimitive("stored-project"))), input["mutedProjectIds"])
                    data("updatePreferences", buildJsonObject { put("version", 4) })
                }
                "WorkOpsNotificationPreferences" -> preferenceResponse(draft)
                else -> error(operation)
            }
        })
        assertFalse(prefs.feedback.failed); assertEquals("Notification preferences saved.", prefs.feedback.message); assertEquals(draft, prefs.draft)
        assertEquals(listOf(scheme), model.schemes); assertEquals("scheme", model.schemeId); assertTrue(model.loaded)
    }

    @Test fun `preference refresh and rejected edits retain existing form behavior without a scheme read`() = runTest {
        val prefs = WorkOpsInboxModel(tab = "preferences", preferencesLoaded = true, draft = draft)
        val model = WorkOpsNotificationsModel(tab = "preferences", preferences = prefs)
        model.retryPreferences(context { operation, _ -> assertEquals("WorkOpsInboxCount", operation); data("unreadCount", JsonPrimitive(8)) })
        assertEquals(draft, prefs.draft); assertEquals(8L, prefs.unreadCount); assertFalse(model.loaded)
        model.savePreferences(attributes(draft.copy(dndStartLocal = "bad-time")), context { _, _ -> error("Must not submit") })
        assertTrue(prefs.feedback.failed); assertEquals("bad-time", prefs.draft.dndStartLocal); assertTrue(prefs.feedback.message.startsWith("Enter the quiet-hours start"))
    }

    @Test fun `cancellation propagates through independent scheme and preference reads`() = runTest {
        val model = WorkOpsNotificationsModel(loaded = true)
        val ctx = context { _, _ -> throw CancellationException("Navigation") }
        assertFailsWith<CancellationException> { model.retry(ctx) }; assertFalse(model.loaded)
        assertFailsWith<CancellationException> { model.retryPreferences(ctx) }; assertFalse(model.preferences.preferencesLoaded)
    }
}
