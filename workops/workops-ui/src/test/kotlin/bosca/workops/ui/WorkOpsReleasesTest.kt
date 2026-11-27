package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.time.OffsetDateTime
import kotlin.test.*

class WorkOpsReleasesTest {
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun json(value: String) = Json.parseToJsonElement(value)
    private fun data(field: String, value: JsonElement) = buildJsonObject { putJsonObject("workOps") { putJsonObject("crossProject") { put(field, value) } } }
    private fun programs() = json("""{"workOps":{"programs":{"all":[{"id":"program","key":"PLAN","name":"Release program"}]}}}""")
    private fun release(id: String, released: Boolean = false) = json("""{"id":"$id","programId":"program","name":"Release $id","description":"Notes","releaseDate":null,"releasedAt":${if (released) "\"2026-12-01T00:00:00Z\"" else "null"},"version":7}""")
    private fun runs(status: String) = data("releaseRuns", if (status.isBlank()) JsonArray(emptyList()) else json("""[{"runId":"native-run","status":"$status","startedAt":"2026-12-01T00:00:00Z"}]"""))
    private fun project(id: String = "project") = WorkOpsPlanningRow(id = id, key = "APP", name = "Application")
    private fun builder() = WorkOpsReleaseBuildModel("program", "Release program", active = true,
        picker = WorkOpsReleasePickerModel("program", projects = listOf(project(), project("other")), pickProjectId = "project", choicesLoaded = true,
        versions = listOf(WorkOpsProjectCatalogRow("v1", "1.0", released = true, sequenceNumber = 1), WorkOpsProjectCatalogRow("v2", "2.0", sequenceNumber = 2))))
    private fun version(id: String = "created") = json("""{"id":"$id","name":"3.0","description":"Notes","released":false,"archived":false,"startDate":null,"releaseDate":null,"sequenceNumber":3,"version":0}""")

    @Test fun `list uses native statuses and distinguishes a failed lookup from a verified draft`() = runTest {
        val statuses = listOf("QUEUED", "RUNNING", "SUCCESS", "FAILURE", "CANCELLED", "SKIPPED", "", "lookup-error", "released")
        val model = WorkOpsReleaseListModel("program")
        val lookedUp = mutableListOf<String>()
        model.reload(context { operation, variables -> when (operation) {
            "WorkOpsReleasePrograms" -> programs()
            "WorkOpsReleaseList" -> data("releases", JsonArray(statuses.map { release(it.ifBlank { "draft" }, it == "released") }))
            "WorkOpsReleaseRunStatuses" -> {
                val id = variables.getValue("id").jsonPrimitive.content; lookedUp += id
                if (id == "lookup-error") error("Run lookup unavailable") else runs(if (id == "draft") "" else id)
            }
            else -> error(operation)
        } })
        assertEquals(listOf("Queued", "Deploying", "Deployed", "Failed", "Cancelled", "Skipped", "Draft", "Status unavailable", "Released"), model.rows.map { it.statusLabel })
        assertFalse("released" in lookedUp); assertTrue(model.loaded); assertFalse(model.feedback.failed)
        assertEquals("Run lookup unavailable", model.rows.first { it.id == "lookup-error" }.statusError)
        model.retryStatus("lookup-error", context { operation, _ -> assertEquals("WorkOpsReleaseRunStatuses", operation); runs("SUCCESS") })
        assertEquals("Deployed", model.rows.first { it.id == "lookup-error" }.statusLabel)
        assertEquals("Unknown status", WorkOpsReleaseRecord("id", "program", "Release", latestStatus = "OK", statusLoaded = true).statusLabel)
    }

    @Test fun `release pages load only displayed run statuses and keep unavailable explicit program context`() = runTest {
        val calls = mutableListOf<String>(); val model = WorkOpsReleaseListModel("program", 25)
        model.reload(context { operation, variables -> when (operation) {
            "WorkOpsReleasePrograms" -> programs()
            "WorkOpsReleaseList" -> data("releases", JsonArray((0..27).map { release("r$it") }))
            else -> { calls += variables.getValue("id").jsonPrimitive.content; runs("") }
        } })
        assertEquals(listOf("r25", "r26", "r27"), calls); assertEquals(3, model.pageRows.size)
        assertEquals("/workops/releases?programId=program&offset=0", model.previousHref)
        val unavailable = WorkOpsReleaseListModel("private")
        unavailable.reload(context { operation, _ -> assertEquals("WorkOpsReleasePrograms", operation); programs() })
        assertEquals("private", unavailable.programId); assertNull(unavailable.selectedProgram); assertTrue(unavailable.rows.isEmpty())
        assertFailsWith<CancellationException> { model.retryStatus("r25", context { _, _ -> throw CancellationException("Navigation") }) }
    }

    @Test fun `build steps retain details and allow distinct versions from the same project without duplicates`() = runTest {
        val model = builder()
        model.detailsNext(" Release ", " Notes ", "2026-12-15"); assertEquals(2, model.draft.step)
        assertEquals(listOf("v2", "v1"), model.picker.offeredVersions.map { it.id }); assertTrue(model.picker.versionChoices.any { it.label == "1.0 · released" })
        model.stage("project", "v1"); model.stage("project", "v2")
        assertEquals(2, model.draft.components.size); assertEquals(1, model.draft.components.map { it.projectId }.distinct().size)
        model.stage("project", "v1"); assertEquals(2, model.draft.components.size); assertTrue(model.picker.feedback.failed)
        model.projectsNext(); model.back(); model.back(); assertEquals(1, model.draft.step)
        assertEquals(" Release ", model.draft.name); assertEquals(" Notes ", model.draft.description); assertEquals("2026-12-15", model.draft.date)
        model.removeStage("v1"); assertEquals(listOf("v2"), model.draft.components.map { it.versionId })
        model.detailsNext("Retained", "Notes", "2026-02-30"); assertTrue(model.feedback.failed); assertEquals(1, model.draft.step)
    }

    @Test fun `changing picker projects clears version create state and cannot stage the old project version`() = runTest {
        val model = builder(); model.picker.openVersion("project"); model.picker.versionDraft.name = "Old project draft"
        model.picker.chooseProject("other", context { operation, variables ->
            assertEquals("WorkOpsDeliveryVersions", operation); assertEquals("other", variables.getValue("project").jsonPrimitive.content)
            json("""{"workOps":{"versions":{"byProject":[]}}}""")
        })
        assertFalse(model.picker.creatingVersion); assertEquals("", model.picker.versionDraft.name); assertEquals("", model.picker.pickVersionId)
        model.stage("project", "v1"); assertTrue(model.picker.feedback.failed); assertTrue(model.draft.components.isEmpty())
    }

    @Test fun `inline version creation retains denied dates and sends optional UTC values`() = runTest {
        val model = builder(); model.picker.openVersion("project")
        model.picker.createVersion(" 3.0 ", " Version notes ", "2026-12-01", "2026-12-15", context { operation, variables ->
            assertEquals("WorkOpsCreateProjectVersion", operation); val input = variables.getValue("input").jsonObject
            assertEquals("project", input.getValue("projectId").jsonPrimitive.content); assertEquals("3.0", input.getValue("name").jsonPrimitive.content)
            assertEquals("2026-12-01T00:00:00Z", OffsetDateTime.parse(input.getValue("startDate").jsonPrimitive.content).toInstant().toString())
            assertEquals("2026-12-15T00:00:00Z", OffsetDateTime.parse(input.getValue("releaseDate").jsonPrimitive.content).toInstant().toString()); error("Denied")
        })
        assertTrue(model.picker.creatingVersion); assertEquals(" 3.0 ", model.picker.versionDraft.name); assertEquals("2026-12-15", model.picker.versionDraft.releaseDate)
        assertTrue(model.picker.versionFeedback.failed)
    }

    @Test fun `a created picker version remains selected and stageable after its follow up read fails`() = runTest {
        val model = builder(); model.picker.openVersion("project")
        model.picker.createVersion("3.0", "Notes", "", "", context { operation, _ ->
            if (operation == "WorkOpsCreateProjectVersion") json("""{"workOps":{"versions":{"create":${version()}}}}""") else error("Read unavailable")
        })
        assertFalse(model.picker.creatingVersion); assertEquals("created", model.picker.pickVersionId); assertFalse(model.picker.versionsLoaded)
        assertTrue(model.picker.feedback.failed); assertTrue(model.picker.versionChoices.any { it.value == "created" })
        model.stage("project", "created"); assertEquals("created", model.draft.components.single().versionId)
    }

    @Test fun `partial bundles retry only pending versions against the same created release`() = runTest {
        val model = builder(); model.detailsNext(" Release ", " Notes ", "2026-12-15")
        model.stage("project", "v1"); model.stage("project", "v2"); model.projectsNext()
        val calls = mutableListOf<String>(); var rejectFirst = true
        val ctx = context { operation, variables -> when (operation) {
            "WorkOpsCreateRelease" -> {
                calls += "create"; val input = variables.getValue("input").jsonObject
                assertEquals("Release", input.getValue("name").jsonPrimitive.content); assertEquals("Notes", input.getValue("description").jsonPrimitive.content)
                assertEquals("2026-12-15T00:00:00Z", OffsetDateTime.parse(input.getValue("releaseDate").jsonPrimitive.content).toInstant().toString())
                data("createRelease", release("created-release"))
            }
            "WorkOpsBundleReleaseVersion" -> {
                val id = variables.getValue("version").jsonPrimitive.content; calls += id
                assertEquals("created-release", variables.getValue("release").jsonPrimitive.content)
                if (id == "v1" && rejectFirst) error("Bundle rejected") else data("bundleVersion", JsonPrimitive(true))
            }
            else -> error(operation)
        } }
        model.build(ctx); assertTrue(model.feedback.failed); assertTrue(model.hasPending); assertEquals(1, model.bundledCount)
        assertEquals("created-release", model.createdId); assertEquals("/workops/releases/created-release", model.createdHref)
        rejectFirst = false; model.close(); model.resume(); model.build(ctx)
        assertEquals(listOf("create", "v1", "v2", "v1"), calls); assertFalse(model.active); assertFalse(model.hasPending)
        assertEquals(2, model.bundledCount); assertEquals("Release built.", model.feedback.message)
    }

    @Test fun `denied release creation retains the review and does not attempt bundles`() = runTest {
        val model = builder(); model.detailsNext("Retained name", "Retained notes", "2026-12-15"); model.stage("project", "v1"); model.projectsNext()
        model.build(context { operation, _ -> assertEquals("WorkOpsCreateRelease", operation); error("Permission denied") })
        assertTrue(model.active); assertEquals(3, model.draft.step); assertEquals("", model.createdId)
        assertEquals("Retained name", model.draft.name); assertEquals(1, model.draft.components.size); assertTrue(model.feedback.failed)
    }

    @Test fun `an empty release is valid and cancellation of bundle work is not swallowed`() = runTest {
        val empty = builder(); empty.detailsNext("Empty release", "", ""); empty.projectsNext()
        empty.build(context { operation, _ -> assertEquals("WorkOpsCreateRelease", operation); data("createRelease", release("empty")) })
        assertFalse(empty.active); assertEquals("Release created.", empty.feedback.message)
        val model = builder(); model.detailsNext("Release", "", ""); model.stage("project", "v1"); model.projectsNext()
        assertFailsWith<CancellationException> { model.build(context { operation, _ ->
            if (operation == "WorkOpsCreateRelease") data("createRelease", release("created")) else throw CancellationException("Navigation")
        }) }
        assertEquals("created", model.createdId); assertTrue(model.hasPending)
    }

    @Test fun `authorized project roots filter picker scope and keep missing selected identities without names`() = runTest {
        val model = builder().apply { picker.pickProjectId = "unavailable"; picker.pickVersionId = "old-version" }
        model.picker.reloadChoices(context { operation, _ ->
            assertEquals("WorkOpsReleasePickerProjects", operation)
            json("""{"workOps":{"projects":{"all":[{"id":"project","key":"APP","name":"Application","program":{"id":"program"}},{"id":"foreign","key":"OTHER","name":"Other program project","program":{"id":"different"}}]}}}""")
        })
        assertEquals(listOf("project"), model.picker.projects.map { it.id }); assertEquals("unavailable", model.picker.pickProjectId)
        assertTrue(model.picker.projectChoices.single { it.value == "unavailable" }.disabled); assertEquals("Project unavailable", model.picker.projectChoices.last().label)
        assertEquals("Version unavailable", model.picker.versionChoices.last().label)
    }

    @Test fun `an authoritative version refresh removes a vanished created choice while failed refreshes retain it`() = runTest {
        val picker = WorkOpsReleasePickerModel("program", projects = listOf(project()), pickProjectId = "project", pickVersionId = "created", choicesLoaded = true,
            createdVersions = listOf(WorkOpsReleaseVersionChoice("project", WorkOpsProjectCatalogRow("created", "3.0")), WorkOpsReleaseVersionChoice("other", WorkOpsProjectCatalogRow("other-created", "4.0"))))
        picker.chooseProject("project", context { _, _ -> error("Refresh unavailable") })
        assertTrue(picker.offeredVersions.any { it.id == "created" })
        picker.reloadPicker("project", "created", context { operation, _ -> when (operation) {
            "WorkOpsReleasePickerProjects" -> json("""{"workOps":{"projects":{"all":[{"id":"project","key":"APP","name":"Application","program":{"id":"program"}}]}}}""")
            "WorkOpsDeliveryVersions" -> json("""{"workOps":{"versions":{"byProject":[]}}}""")
            else -> error(operation)
        } })
        assertFalse(picker.feedback.failed); assertEquals(listOf("other-created"), picker.createdVersions.map { it.item.id })
        assertTrue(picker.offeredVersions.isEmpty()); assertEquals("created", picker.pickVersionId)
        assertEquals("Version unavailable", picker.versionChoices.single().label); assertTrue(picker.versionChoices.single().disabled)
        assertFailsWith<IllegalArgumentException> { picker.selection("project", "created") }
    }
}
