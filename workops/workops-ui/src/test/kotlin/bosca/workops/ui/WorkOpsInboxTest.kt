package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsInboxTest {
    @Test fun `mark read requires the displayed row and honors a refused API result before refreshing`() = runTest {
        val model = WorkOpsInboxModel()
        model.markRead("missing", context { _, _ -> throw AssertionError("Missing notifications cannot be written") })
        assertEquals("Reload the inbox before changing this notification.", model.feedback.message)
        model.rows = listOf(WorkOpsNotificationRow("notification", "TASK_CREATED", "Body", "/workops/tasks/task", false, "2026-11-17"))
        var allow = false
        val ctx = context { operation, _ -> when (operation) {
            "WorkOpsMarkNotificationRead" -> json("""{"workOps":{"notifications":{"markRead":$allow}}}""")
            "WorkOpsUnreadInbox" -> json("""{"workOps":{"notifications":{"unreadCount":0,"unread":[]}}}""")
            else -> error(operation)
        } }
        model.markRead("notification", ctx)
        assertEquals("This notification is unavailable.", model.feedback.message)
        assertFalse(model.rows.single().read)
        allow = true
        model.markRead("notification", ctx)
        assertFalse(model.feedback.failed)
        assertTrue(model.rows.isEmpty())
        assertEquals("Notification marked read.", model.feedback.message)
    }
    private val draftJson = Json { encodeDefaults = true }
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun json(source: String) = Json.parseToJsonElement(source)
    private fun attributes(draft: WorkOpsNotificationDraft) = draftJson.encodeToString(WorkOpsNotificationDraft.serializer(), draft)

    @Test fun `missing channel entries keep defaults while explicit empty choices and untouched keys survive`() {
        val initial = WorkOpsNotificationDraft(eventChannels = json("""{"TASK_CREATED":[],"LEGACY_EVENT":["EMAIL"]}""").jsonObject).withFlags()
        assertEquals(emptySet(), initial.channelsFor("TASK_CREATED"))
        assertEquals(setOf("IN_APP", "EMAIL"), initial.channelsFor("TASK_UPDATED"))
        assertEquals(initial.eventChannels, initial.updatedChannels())
        val changed = initial.copy(flags = initial.flags + ("TASK_UPDATED" to mapOf("IN_APP" to false, "EMAIL" to false, "WEBHOOK" to false, "SLACK" to false)))
        assertEquals(JsonArray(emptyList()), changed.updatedChannels()["TASK_UPDATED"])
        assertEquals(JsonArray(listOf(JsonPrimitive("EMAIL"))), changed.updatedChannels()["LEGACY_EVENT"])
        assertEquals(JsonArray(emptyList()), changed.updatedChannels()["TASK_CREATED"])
    }

    @Test fun `failed preference writes retain booleans times channel intent and hidden mute lists`() = runTest {
        val draft = WorkOpsNotificationDraft(watchAuthored = false, dailyDigest = true, dndStartLocal = "22:00", dndEndLocal = "08:00",
            eventChannels = json("""{"LEGACY_EVENT":["EMAIL"]}""").jsonObject, mutedTaskIds = listOf("task"), mutedProjectIds = listOf("project")).withFlags()
        val model = WorkOpsInboxModel(tab = "preferences", preferencesLoaded = true)
        model.savePreferences(attributes(draft), context { operation, variables ->
            assertEquals("WorkOpsSaveNotificationPreferences", operation)
            val input = variables.getValue("input").jsonObject
            assertFalse(input.getValue("watchAuthored").jsonPrimitive.boolean)
            assertTrue(input.getValue("dailyDigest").jsonPrimitive.boolean)
            assertEquals(draft.eventChannels, input.getValue("eventChannels"))
            assertEquals(JsonArray(listOf(JsonPrimitive("task"))), input.getValue("mutedTaskIds"))
            assertEquals(JsonArray(listOf(JsonPrimitive("project"))), input.getValue("mutedProjectIds"))
            error("Preferences unavailable")
        })
        assertTrue(model.feedback.failed)
        assertTrue(model.preferencesLoaded)
        assertEquals(draft, model.draft)
    }

    @Test fun `quiet time validation retains the draft and cancellation is not converted to feedback`() = runTest {
        val model = WorkOpsInboxModel(tab = "preferences", preferencesLoaded = true)
        val draft = WorkOpsNotificationDraft(dndStartLocal = "bad-time").withFlags()
        model.savePreferences(attributes(draft), context { _, _ -> error("Invalid time must not submit") })
        assertEquals(draft, model.draft)
        assertTrue(model.feedback.message.startsWith("Enter the quiet-hours start"))
        assertFailsWith<CancellationException> {
            model.savePreferences(attributes(draft.copy(dndStartLocal = "22:00:30")), context { _, _ -> throw CancellationException("closed") })
        }
    }

    @Test fun `refreshing a loaded preference view updates counts without discarding preference state`() = runTest {
        val draft = WorkOpsNotificationDraft(watchCommented = false, dndStartLocal = "22:00").withFlags()
        val model = WorkOpsInboxModel(tab = "preferences", preferencesLoaded = true, draft = draft)
        model.reload(context { operation, _ ->
            assertEquals("WorkOpsInboxCount", operation)
            json("""{"workOps":{"notifications":{"unreadCount":9}}}""")
        })
        assertEquals(9L, model.unreadCount)
        assertEquals(draft, model.draft)
    }

    @Test fun `inbox pages retain a thirty item window and count without exposing unsafe destinations`() = runTest {
        val model = WorkOpsInboxModel(offset = 30)
        model.reload(context { operation, variables ->
            assertEquals("WorkOpsUnreadInbox", operation)
            assertEquals(30L, variables.getValue("offset").jsonPrimitive.long)
            buildJsonObject { putJsonObject("workOps") { putJsonObject("notifications") {
                put("unreadCount", 80)
                putJsonArray("unread") { repeat(31) { add(buildJsonObject {
                    put("id", "notification-$it"); put("event", "TASK_CREATED"); put("body", "Created a task")
                    put("link", "//external.test/path"); put("taskId", "task"); put("projectId", JsonNull)
                    put("readAt", JsonNull); put("createdAt", "2026-11-13T12:00:00Z")
                }) } }
            } } }
        })
        assertEquals(30, model.rows.size)
        assertEquals(80L, model.unreadCount)
        assertEquals("/workops/tasks/task", model.rows.first().href)
        assertEquals("/workops/inbox?tab=unread&offset=0", model.previousHref)
        assertEquals("/workops/inbox?tab=unread&offset=60", model.nextPageHref)
    }

    @Test fun `opening an unread notification navigates only after its owner API acknowledges the read`() = runTest {
        val row = WorkOpsNotificationRow("notification", "TASK_CREATED", "Created a task", "/workops/tasks/task", false, "2026-11-13T12:00:00Z")
        val model = WorkOpsInboxModel(rows = listOf(row))
        model.open(row.id, context { operation, _ ->
            assertEquals("WorkOpsMarkNotificationRead", operation)
            json("""{"workOps":{"notifications":{"markRead":false}}}""")
        })
        assertTrue(model.feedback.failed)
        assertEquals("", model.nextHref)
        model.open(row.id, context { _, _ -> json("""{"workOps":{"notifications":{"markRead":true}}}""") })
        assertEquals(row.href, model.nextHref)
        assertFalse(model.feedback.failed)
    }
}
