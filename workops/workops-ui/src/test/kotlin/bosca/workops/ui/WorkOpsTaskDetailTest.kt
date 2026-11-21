package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.bml.render.withRenderContext
import bosca.workops.ui.graphql.WorkOpsTaskDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.time.LocalDate
import kotlin.test.*

class WorkOpsTaskDetailTest {
    private val id = "10000000-0000-0000-0000-000000000001"
    private val taskJson = Json.parseToJsonElement("""{
      "profiles":{"current":[{"id":"me","name":"Me"}]},
      "workOps":{
        "tasks":{"task":{
          "id":"$id","key":"TEST-1","summary":"Original","descriptionMarkdown":"A description","metadataId":null,
          "version":7,"createdAt":"2026-11-13T10:00:00Z","modifiedAt":"2026-11-13T10:00:00Z",
          "project":{"id":"project","key":"TEST","name":"Project"},"affectedProjects":[],
          "status":{"name":"To Do","category":"TODO"},"taskType":{"name":"Task"},"priority":{"id":"priority","name":"Medium","colorHex":"#91a6f6"},
          "resolution":null,"assigneeProfileId":null,"assignee":null,"reporterProfileId":"me",
          "dueDate":null,"sprintId":null,"originalEstimateSeconds":null,"remainingEstimateSeconds":null,"timeSpentSeconds":0,
          "parentTask":null,"epicTask":null,"requirement":null,
          "transitions":[{"id":"done","name":"Resolve","toState":{"status":{"category":"DONE"}}}],"comments":[],"links":[],"history":[]
        },"priorities":[{"id":"priority","name":"Medium"}],"resolutions":[{"id":"resolution","name":"Done"}]},
        "notifications":{"watchers":[{"profileId":"me"}]},"worklogs":{"forTask":[]},"links":{"linkTypes":[]},"projects":{"all":[]}
      }
    }""")
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun model() = WorkOpsTaskDetailModel(id, data = WorkOpsTaskDetail.decodeData(taskJson))

    @Test fun `new assignees are resolved once and zero estimates retain exact mutation values on rejection`() = runTest {
        val page = model().apply { editing = true; draft = WorkOpsTaskDraft(expectedVersion = 7) }
        page.save("Draft", "Body", "priority", "unavailable", "", "", "", "", context { operation, _ ->
            assertEquals("WorkOpsTaskProfile", operation)
            Json.parseToJsonElement("""{"profiles":{"profile":null}}""")
        })
        assertEquals("The selected assignee is unavailable.", page.feedback.message)
        var lookups = 0
        var writes = 0
        val ctx = context { operation, variables -> when (operation) {
            "WorkOpsTaskProfile" -> {
                lookups++
                Json.parseToJsonElement("""{"profiles":{"profile":{"id":"new-person","name":"Person"}}}""")
            }
            "WorkOpsUpdateTask" -> {
                writes++
                val input = variables.getValue("input").jsonObject
                assertEquals(JsonPrimitive(0), input["originalEstimateSeconds"])
                assertEquals(JsonPrimitive(0), input["remainingEstimateSeconds"])
                assertEquals(JsonPrimitive(7), input["expectedVersion"])
                assertEquals("new-person", input.getValue("assigneeProfileId").jsonPrimitive.content)
                error("Newer task")
            }
            else -> error(operation)
        } }
        repeat(2) { page.save("Draft", "Body", "priority", "new-person", "", "2026-12-01", "0", "0m", ctx) }
        assertEquals(1, lookups)
        assertEquals(2, writes)
        assertEquals("Newer task", page.feedback.message)
        assertTrue(page.editing)
        assertEquals("0m", page.draft.remainingEstimate)
    }

    @Test fun `unavailable task presentation clears counts and all activity pages without inventing read results`() {
        for (tab in listOf("comments", "history", "worklog")) {
            val page = WorkOpsTaskDetailModel(id, tab)
            assertEquals("Task not found", page.title)
            assertFalse(page.watching)
            assertFalse(page.hasNext)
            assertEquals(0, page.watcherCount)
            assertEquals("", page.nextHref)
            assertEquals(emptyList(), page.availableProjects)
            assertEquals("neutral", page.statusTone)
            assertEquals("—", page.duration(null))
            page.feedback.failed = true
            assertEquals("Could not load task", page.title)
        }
    }

    @Test fun `watching requires a current profile and affected project choices exclude existing membership`() {
        val changed = Json.parseToJsonElement(taskJson.toString()
            .replace("\"metadataId\":null", "\"metadataId\":null,\"descriptionHtml\":null,\"startDate\":null")
            .replace("\"affectedProjects\":[]", "\"affectedProjects\":[{\"id\":\"affected\",\"key\":\"OTHER\",\"name\":\"Affected\"}]")
            .replace("\"all\":[]", "\"all\":[{\"id\":\"project\",\"key\":\"TEST\",\"name\":\"Primary\"},{\"id\":\"affected\",\"key\":\"OTHER\",\"name\":\"Affected\"},{\"id\":\"available\",\"key\":\"NEW\",\"name\":\"Available\"}]"))
        val page = assertWorkOpsSerialization(WorkOpsTaskDetailModel.serializer(), """{"id":"$id"}""", """{"id":"$id","data":$changed}""")
        assertEquals(listOf("available"), page.availableProjects.map { it.id })
        assertTrue(page.watching)
        val loaded = requireNotNull(page.data)
        page.data = loaded.copy(profiles = loaded.profiles.copy(current = null))
        assertFalse(page.watching)
        page.data = loaded.copy(workOps = loaded.workOps.copy(tasks = loaded.workOps.tasks.copy(task = null)))
        assertFalse(page.hasNext)
        val task = requireNotNull(loaded.workOps.tasks.task)
        for ((category, tone) in listOf(bosca.workops.ui.graphql.WorkOpsStatusCategory.DONE to "success",
            bosca.workops.ui.graphql.WorkOpsStatusCategory.IN_PROGRESS to "purple")) {
            page.data = loaded.copy(workOps = loaded.workOps.copy(tasks = loaded.workOps.tasks.copy(task = task.copy(status = task.status.copy(category = category)))))
            assertEquals(tone, page.statusTone)
        }
    }

    @Test fun `due dates before the UTC calendar day remain overdue regardless of task completion`() {
        val today = LocalDate.of(2026, 3, 1)
        for (category in listOf("TODO", "DONE")) {
            for ((date, overdue) in listOf(null to false, "2026-02-28T23:59:59Z" to true,
                "2026-03-01T00:00:00Z" to false, "2026-03-01T23:59:59Z" to false, "2026-03-02T00:00:00Z" to false)) {
                val data = taskJson.toString().replace("\"dueDate\":null", "\"dueDate\":${JsonPrimitive(date)}")
                    .replace("\"category\":\"TODO\"", "\"category\":\"$category\"")
                val model = WorkOpsTaskDetailModel(id, data = WorkOpsTaskDetail.decodeData(Json.parseToJsonElement(data)))
                assertEquals(overdue, model.dueDateOverdue(today), "$category $date")
                assertEquals(date, model.task?.dueDate)
            }
        }
    }

    @Test fun `summary history presents document names without rewriting stored values or authored body text`() {
        val model = model()
        for ((stored, display) in listOf("TEST-REQ-27" to "Requirement", "REQ TEST-REQ-27" to "Requirement", "TEST-SPEC-12" to "Specification", "Authored requirement notes" to "Authored requirement notes")) {
            val value = JsonPrimitive(stored)
            assertEquals(display, model.historyValue("summary", value))
            assertEquals(stored, value.content)
            assertEquals(value.toString(), model.historyValue("description_markdown", value))
        }
        assertEquals("Unset", model.historyValue("summary", null))
        assertEquals("null", model.historyValue("summary", JsonNull))
        assertEquals("7", model.historyValue("original_estimate_seconds", JsonPrimitive(7)))
        assertEquals("Original", model.task?.summary)
    }

    @Test fun `an unchanged display name retains its generated summary while explicit renaming still works`() = runTest {
        val legacy = Json.parseToJsonElement(taskJson.toString().replace("Original", "REQ TEST-REQ-27"))
        val model = WorkOpsTaskDetailModel(id, data = WorkOpsTaskDetail.decodeData(legacy))
        val noReads = context { operation, _ -> error("Unexpected $operation") }
        assertEquals("Requirement", model.title)
        model.edit(noReads)
        assertEquals("Requirement", model.draft.summary)
        assertEquals("A description", model.draft.description)
        for ((display, stored) in listOf("Requirement" to "REQ TEST-REQ-27", "Authored new name" to "Authored new name")) {
            model.save(display, "Authored REQ TEST-REQ-27 body", "priority", "", "", "", "", "", context { operation, variables ->
                assertEquals("WorkOpsUpdateTask", operation)
                val input = variables.getValue("input").jsonObject
                assertEquals(stored, input.getValue("summary").jsonPrimitive.content)
                assertEquals("Authored REQ TEST-REQ-27 body", input.getValue("descriptionMarkdown").jsonPrimitive.content)
                assertEquals(7L, input.getValue("expectedVersion").jsonPrimitive.long)
                error("Retained stale edit")
            })
            assertTrue(model.feedback.failed)
            assertEquals(display, model.draft.summary)
            assertEquals("REQ TEST-REQ-27", model.task?.summary)
        }
        val literal = WorkOpsTaskDetailModel(id, data = WorkOpsTaskDetail.decodeData(Json.parseToJsonElement(taskJson.toString().replace("Original", "Task"))))
        literal.edit(noReads); assertEquals("Task", literal.title); assertEquals("Task", literal.draft.summary)
    }

    @Test fun `task activity links preserve the independent requirements page`() {
        val page = WorkOpsTaskDetailModel(id, tab = "history", activityOffset = 25, requirementsOffset = 50)
        assertEquals("/workops/tasks/$id?tab=history&offset=25", page.requirementsHref)
        assertEquals("/workops/tasks/$id?tab=history&offset=0&requirementsOffset=50", page.previousHref)
        assertTrue(page.activityTabs.all { it.href.endsWith("&requirementsOffset=50") })
    }

    @Test fun `task editing without project view retains its sprint without fetching inaccessible board choices`() = runTest {
        val restricted = Json.parseToJsonElement(taskJson.toString().replace("\"sprintId\":null", "\"sprintId\":\"private-sprint\""))
        val model = WorkOpsTaskDetailModel(id)
        val ctx = context { operation, _ -> assertEquals("WorkOpsTaskDetail", operation); restricted }
        model.reload(ctx); model.edit(ctx)
        assertTrue(model.editing); assertFalse(model.feedback.failed)
        assertEquals("private-sprint", model.draft.sprintId); assertEquals(7L, model.draft.expectedVersion)
        assertEquals("Sprint unavailable", model.sprintName)
        assertEquals("private-sprint", model.sprints.single().id)
    }

    @Test fun `authorized board lookup failures remain explicit when opening a task edit`() = runTest {
        val visible = Json.parseToJsonElement(taskJson.toString().replace("\"projects\":{\"all\":[]}", "\"projects\":{\"all\":[{\"id\":\"project\",\"key\":\"TEST\",\"name\":\"Project\"}]}"))
        val model = WorkOpsTaskDetailModel(id, data = WorkOpsTaskDetail.decodeData(visible))
        model.edit(context { operation, _ -> assertEquals("WorkOpsTaskBoards", operation); error("Board API unavailable") })
        assertFalse(model.editing); assertTrue(model.feedback.failed); assertEquals("Board API unavailable", model.feedback.message)
    }

    @Test fun `key links resolve once and task watches use the notification relation`() = runTest {
        val operations = mutableListOf<String>()
        val gql = context { operation, variables ->
            operations += operation
            when (operation) {
                "WorkOpsTaskIdByKey" -> { assertEquals("TEST-1", variables.getValue("key").jsonPrimitive.content); Json.parseToJsonElement("""{"workOps":{"tasks":{"taskByKey":{"id":"$id"}}}}""") }
                "WorkOpsTaskDetail" -> { assertEquals(id, variables.getValue("id").jsonPrimitive.content); taskJson }
                else -> error(operation)
            }
        }.gql
        val loaded = withRenderContext(RenderContext(gql = gql, params = mapOf("id" to "test-1"), query = mapOf("tab" to "history", "offset" to "-1"))) { workOpsTaskDetail() }
        assertEquals(listOf("WorkOpsTaskIdByKey", "WorkOpsTaskDetail"), operations)
        assertTrue(loaded.watching)
        assertEquals(1, loaded.watcherCount)
        assertEquals(0L, loaded.activityOffset)
        assertEquals("history", loaded.tab)
        assertEquals("Me", loaded.profileName("me"))
        assertEquals("/workops/tasks/$id", loaded.href)
    }

    @Test fun `an inaccessible reporter profile does not prevent task detail loading`() = runTest {
        val model = WorkOpsTaskDetailModel(id)
        model.reload(context { operation, variables -> when (operation) {
            "WorkOpsTaskDetail" -> Json.parseToJsonElement(taskJson.toString().replace("\"reporterProfileId\":\"me\"", "\"reporterProfileId\":\"private-profile\""))
            "WorkOpsTaskProfile" -> {
                assertEquals("private-profile", variables.getValue("id").jsonPrimitive.content)
                Json.parseToJsonElement("""{"profiles":{"profile":null}}""")
            }
            else -> error(operation)
        } })
        assertEquals("Original", model.title)
        assertEquals("Profile unavailable", model.profileName(model.task?.reporterProfileId))
    }

    @Test fun `saving another field retains an unchanged inaccessible assignee`() = runTest {
        val privateAssignee = Json.parseToJsonElement(taskJson.toString().replace("\"assigneeProfileId\":null", "\"assigneeProfileId\":\"private-assignee\""))
        val model = WorkOpsTaskDetailModel(id, data = WorkOpsTaskDetail.decodeData(privateAssignee), editing = true,
            draft = WorkOpsTaskDraft(expectedVersion = 7))
        model.save("Revised", "Description", "priority", "private-assignee", "", "", "", "", context { operation, variables -> when (operation) {
            "WorkOpsUpdateTask" -> {
                val input = variables.getValue("input").jsonObject
                assertEquals("private-assignee", input.getValue("assigneeProfileId").jsonPrimitive.content)
                assertFalse(input.getValue("clearAssignee").jsonPrimitive.boolean)
                Json.parseToJsonElement("""{"workOps":{"tasks":{"update":{"id":"$id"}}}}""")
            }
            "WorkOpsTaskDetail" -> privateAssignee
            else -> error("Unexpected $operation")
        } })
        assertFalse(model.feedback.failed)
        assertFalse(model.editing)
        assertEquals("private-assignee", model.task?.assigneeProfileId)
        assertEquals("Profile unavailable", model.assigneeLabel)
    }

    @Test fun `failed edits retain all values and the original version with explicit clear flags`() = runTest {
        val model = model().apply { editing = true; draft = WorkOpsTaskDraft(expectedVersion = 7) }
        model.save("Revised", "**draft**", "priority", "", "", "", "0m", "2h 30m", context { operation, variables ->
            assertEquals("WorkOpsUpdateTask", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals(7L, input.getValue("expectedVersion").jsonPrimitive.long)
            for (flag in listOf("clearAssignee", "clearSprintId", "clearDueDate")) assertTrue(input.getValue(flag).jsonPrimitive.boolean)
            assertEquals(0L, input.getValue("originalEstimateSeconds").jsonPrimitive.long)
            assertEquals(9000L, input.getValue("remainingEstimateSeconds").jsonPrimitive.long)
            error("A newer version was saved")
        })
        assertTrue(model.editing)
        assertTrue(model.feedback.failed)
        assertEquals("Revised", model.draft.summary)
        assertEquals("**draft**", model.draft.description)
        assertEquals(7L, model.draft.expectedVersion)
        assertEquals("A newer version was saved", model.feedback.message)
        assertEquals("Original", model.task?.summary)
        assertFailsWith<CancellationException> { model.save("Revised", "", "priority", "", "", "", "", "", context { _, _ -> throw CancellationException("closed") }) }
    }

    @Test fun `terminal transitions require a chosen resolution and use the current task version`() = runTest {
        val model = model()
        val noRequests = context { operation, _ -> error("Unexpected $operation") }
        model.beginTransition("done", noRequests)
        assertEquals("done", model.pendingTransition)
        model.completeTransition("", noRequests)
        assertTrue(model.feedback.failed)
        assertEquals("done", model.pendingTransition)
        model.completeTransition("resolution", context { operation, variables ->
            when (operation) {
                "WorkOpsTransitionTask" -> {
                    assertEquals(7L, variables.getValue("expectedVersion").jsonPrimitive.long)
                    assertEquals("resolution", variables.getValue("resolutionId").jsonPrimitive.content)
                    Json.parseToJsonElement("""{"workOps":{"tasks":{"transition":{"id":"$id"}}}}""")
                }
                "WorkOpsTaskDetail" -> taskJson
                else -> error(operation)
            }
        })
        assertFalse(model.feedback.failed)
        assertEquals("", model.pendingTransition)
    }

    @Test fun `comment errors retain the draft and successful comments rotate the field preservation key`() = runTest {
        val model = model()
        model.comment("Retain my comment", context { _, _ -> error("Comment rejected") })
        assertEquals("Retain my comment", model.commentDraft)
        assertEquals(0L, model.commentRevision)
        model.comment("Retain my comment", context { operation, _ -> when (operation) {
            "WorkOpsCommentOnTask" -> Json.parseToJsonElement("""{"workOps":{"taskComments":{"add":{"id":1}}}}""")
            "WorkOpsTaskDetail" -> taskJson
            else -> error(operation)
        } })
        assertFalse(model.feedback.failed)
        assertEquals("", model.commentDraft)
        assertEquals(1L, model.commentRevision)
    }

    @Test fun `work log submission uses the owning visibility enum UTC and retains a rejected draft`() = runTest {
        val model = model().apply { loggingWork = true }
        model.logWork("30m", "2026-11-13T12:30", "Work detail", "15m", context { operation, variables ->
            assertEquals("WorkOpsLogTaskWork", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals("ALL", input.getValue("visibility").jsonPrimitive.content)
            assertEquals("2026-11-13T12:30:00Z", input.getValue("startedAt").jsonPrimitive.content)
            error("Work log rejected")
        })
        assertTrue(model.feedback.failed)
        assertTrue(model.loggingWork)
        assertEquals("30m", model.workTime)
        assertEquals("Work detail", model.workComment)
        assertEquals("15m", model.estimateAdjustment)
    }

    @Test fun `work log deletion requires confirmation and an inspected entry and reports a refused mutation`() = runTest {
        val withLog = Json.parseToJsonElement(taskJson.toString().replace(
            "\"forTask\":[]", "\"forTask\":[{\"id\":\"entry\",\"profileId\":\"me\",\"timeSpentShort\":\"30m\",\"startedAt\":\"2026-11-13T12:30:00Z\",\"comment\":null}]"))
        val model = WorkOpsTaskDetailModel(id, data = WorkOpsTaskDetail.decodeData(withLog))
        val noWrite = context { operation, _ -> error("Unexpected $operation") }
        model.deleteWork("entry", false, noWrite)
        assertEquals("Confirm work log deletion.", model.feedback.message)
        model.deleteWork("missing", true, noWrite)
        assertEquals("Reload this task's work log.", model.feedback.message)
        model.deleteWork("entry", true, context { operation, variables ->
            assertEquals("WorkOpsDeleteTaskWork", operation)
            assertEquals("entry", variables.getValue("id").jsonPrimitive.content)
            Json.parseToJsonElement("""{"workOps":{"worklogs":{"deleteWorklog":false}}}""")
        })
        assertTrue(model.feedback.failed)
        assertEquals("The work log was not deleted. Reload the task before trying again.", model.feedback.message)
        assertEquals("entry", model.data?.workOps?.worklogs?.forTask?.single()?.id)
    }

    @Test fun `accepted work log deletion reloads recorded time and the current activity page`() = runTest {
        val withLog = Json.parseToJsonElement(taskJson.toString().replace(
            "\"forTask\":[]", "\"forTask\":[{\"id\":\"entry\",\"profileId\":\"me\",\"timeSpentShort\":\"30m\",\"startedAt\":\"2026-11-13T12:30:00Z\",\"comment\":null}]"))
        val model = WorkOpsTaskDetailModel(id, tab = "worklog", activityOffset = 25, data = WorkOpsTaskDetail.decodeData(withLog))
        val operations = mutableListOf<String>()
        model.deleteWork("entry", true, context { operation, variables ->
            operations += operation
            when (operation) {
                "WorkOpsDeleteTaskWork" -> Json.parseToJsonElement("""{"workOps":{"worklogs":{"deleteWorklog":true}}}""")
                "WorkOpsTaskDetail" -> {
                    assertEquals(25L, variables.getValue("activityOffset").jsonPrimitive.long)
                    taskJson
                }
                else -> error(operation)
            }
        })
        assertEquals(listOf("WorkOpsDeleteTaskWork", "WorkOpsTaskDetail"), operations)
        assertFalse(model.feedback.failed)
        assertEquals("Work log deleted.", model.feedback.message)
        assertTrue(model.data?.workOps?.worklogs?.forTask.orEmpty().isEmpty())
        assertEquals(0L, model.task?.timeSpentSeconds)
    }

    @Test fun `activity pagination uses a lookahead for the selected tab and retains requirement navigation`() {
        val root = taskJson.jsonObject
        val workOps = root.getValue("workOps").jsonObject
        for (tab in listOf("comments", "history", "worklog")) for (count in listOf(0, 25, 26)) {
            val rows = JsonArray(List(count) { index -> when (tab) {
                "comments" -> buildJsonObject {
                    put("id", index.toLong()); put("content", "Comment"); put("profileId", "me")
                    put("profile", JsonNull); put("created", "2026-11-13T12:30:00Z"); put("modified", "2026-11-13T12:30:00Z"); put("likes", 0)
                }
                "history" -> buildJsonObject {
                    put("id", "history-$index"); put("changes", JsonArray(emptyList())); put("changedByProfileId", "me"); put("changedAt", "2026-11-13T12:30:00Z")
                }
                else -> buildJsonObject {
                    put("id", "work-$index"); put("profileId", "me"); put("timeSpentShort", "30m"); put("startedAt", "2026-11-13T12:30:00Z"); put("comment", JsonNull)
                }
            } })
            val tasks = workOps.getValue("tasks").jsonObject
            val task = tasks.getValue("task").jsonObject
            val updated = if (tab == "worklog") workOps + ("worklogs" to buildJsonObject { put("forTask", rows) })
                else workOps + ("tasks" to JsonObject(tasks + ("task" to JsonObject(task + (tab to rows)))))
            val model = WorkOpsTaskDetailModel(id, tab, 25, WorkOpsTaskDetail.decodeData(JsonObject(root + ("workOps" to JsonObject(updated)))), requirementsOffset = 50)
            assertEquals(count == 26, model.hasNext, "$tab: $count")
            assertEquals(if (count == 26) "/workops/tasks/$id?tab=$tab&offset=50&requirementsOffset=50" else "", model.nextHref)
            assertEquals("/workops/tasks/$id?tab=$tab&offset=0&requirementsOffset=50", model.previousHref)
        }
    }
}
