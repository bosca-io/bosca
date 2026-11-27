package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.time.OffsetDateTime
import kotlin.test.*

class WorkOpsReleaseDetailTest {
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun json(s: String) = Json.parseToJsonElement(s)
    private fun data(field: String, value: JsonElement) = buildJsonObject { putJsonObject("workOps") { putJsonObject("crossProject") { put(field, value) } } }
    private fun release(version: Long = 7, name: String = "Release") = json("""{"id":"release","programId":"program","name":"$name","description":"Notes","releaseDate":"2027-01-15T00:00:00Z","releasedAt":null,"version":$version}""")
    private fun rawComponent(project: String = "project", version: String = "v1", status: String = "PENDING") = json("""{"releaseId":"release","projectId":"$project","versionId":"$version","deploymentOrder":null,"deploymentStatus":"$status","deployedAt":null,"rollbackVersionId":null}""")
    private fun projects() = json("""{"workOps":{"projects":{"all":[{"id":"project","key":"APP","name":"Application"}]}}}""")
    private fun pickerProjects() = json("""{"workOps":{"projects":{"all":[{"id":"project","key":"APP","name":"Application","program":{"id":"program"}}]}}}""")
    private fun versions() = json("""{"workOps":{"versions":{"byProject":[{"id":"v1","name":"1.0","description":null,"released":true,"archived":false,"startDate":null,"releaseDate":null,"sequenceNumber":1,"version":0},{"id":"v2","name":"2.0","description":null,"released":false,"archived":false,"startDate":null,"releaseDate":null,"sequenceNumber":2,"version":0}]}}}""")
    private fun editor() = WorkOpsReleaseEditModel("release", WorkOpsReleaseRecord("release", "program", "Release", version = 7), canManage = true,
        editing = WorkOpsReleaseRecord("release", "program", "Release", version = 7), nameDraft = "Release", descriptionDraft = "Notes", dateDraft = "2027-01-15",
        picker = WorkOpsReleasePickerModel("program", projects = listOf(WorkOpsPlanningRow(id = "project", key = "APP", name = "Application")), pickProjectId = "project", choicesLoaded = true, versionsLoaded = true,
            versions = listOf(WorkOpsProjectCatalogRow("v1", "1.0", sequenceNumber = 1), WorkOpsProjectCatalogRow("v2", "2.0", sequenceNumber = 2))), componentsLoaded = true)

    @Test fun `detail resolves only authorized project names and versions while retaining bundle deployment facts`() = runTest {
        val lookedUp = mutableListOf<String>(); val model = WorkOpsReleaseDetailModel("release")
        model.retry(context { operation, variables -> when (operation) {
            "WorkOpsReleaseDetail" -> data("release", release())
            "WorkOpsReleasePrograms" -> json("""{"workOps":{"programs":{"all":[{"id":"program","key":"PLAN","name":"Program"}]}}}""")
            "WorkOpsReleaseRunStatuses" -> data("releaseRuns", json("""[{"runId":"native-run","status":"SUCCESS","startedAt":"2027-01-01T00:00:00Z"}]"""))
            "WorkOpsReleaseComponents" -> data("versionsForRelease", JsonArray(listOf(rawComponent(status = "DEPLOYED"), rawComponent("private", "hidden"))))
            "WorkOpsVersionProjects" -> projects()
            "WorkOpsDeliveryVersions" -> { lookedUp += variables.getValue("project").jsonPrimitive.content; versions() }
            "WorkOpsReleaseReadiness" -> data("releaseReadiness", JsonArray(listOf(JsonPrimitive("Provider version required"))))
            "WorkOpsReleaseAccess" -> data("releaseActionAccess", json("""{"canManage":false}"""))
            else -> error(operation)
        } })
        assertFalse(model.feedback.failed); assertEquals(listOf("project"), lookedUp)
        assertEquals("APP · Application", model.components.first().projectLabel); assertEquals("1.0", model.components.first().versionLabel)
        assertEquals("Project unavailable", model.components.last().projectLabel); assertEquals("Version unavailable", model.components.last().versionLabel); assertEquals("", model.components.last().href)
        assertEquals("Deploying", model.lifecycle); assertEquals(1, model.deployedCount); assertEquals(1, model.pendingCount)
        assertEquals(listOf("Provider version required"), model.readiness); assertTrue(model.readinessLoaded); assertTrue(model.accessLoaded); assertFalse(model.canManage)
    }

    @Test fun `read failures do not manufacture draft ready or zero deployment state and cancellation propagates`() = runTest {
        val model = WorkOpsReleaseDetailModel("release", canManage = true, accessLoaded = true)
        model.retry(context { operation, _ -> when (operation) {
            "WorkOpsReleaseDetail" -> data("release", release())
            "WorkOpsReleasePrograms" -> json("""{"workOps":{"programs":{"all":[]}}}""")
            else -> error("Unavailable $operation")
        } })
        assertEquals("Status unavailable", model.lifecycle); assertFalse(model.componentsLoaded); assertFalse(model.readinessLoaded)
        assertFalse(model.accessLoaded); assertFalse(model.canManage); assertTrue(model.readinessFeedback.failed); assertTrue(model.componentFeedback.failed)
        assertTrue(model.release?.statusError.orEmpty().contains("WorkOpsReleaseRunStatuses"))
        model.release = model.release?.copy(released = "2027-01-01T00:00:00Z"); assertEquals("Released", model.lifecycle)
        assertFailsWith<CancellationException> { model.retry(context { _, _ -> throw CancellationException("Navigation") }) }
    }

    @Test fun `choice refresh preserves unsaved details and the inspected release revision before a rejected save`() = runTest {
        val model = editor()
        model.reloadChoices(" Retained ", " Draft notes ", "2027-02-15", "project", "v2", context { operation, _ -> when (operation) {
            "WorkOpsReleasePickerProjects" -> pickerProjects()
            "WorkOpsDeliveryVersions" -> versions()
            "WorkOpsReleaseComponents" -> data("versionsForRelease", JsonArray(emptyList()))
            else -> error("Unexpected revision reload: $operation")
        } })
        assertEquals(7L, model.editing?.version); assertEquals(" Retained ", model.nameDraft); assertEquals("v2", model.picker.pickVersionId)
        model.save(model.nameDraft, model.descriptionDraft, model.dateDraft, "project", "v2", context { operation, variables ->
            assertEquals("WorkOpsUpdateRelease", operation); assertEquals(7L, variables.getValue("version").jsonPrimitive.long)
            val input = variables.getValue("input").jsonObject; assertEquals("Retained", input.getValue("name").jsonPrimitive.content)
            assertEquals("Draft notes", input.getValue("description").jsonPrimitive.content)
            assertEquals("2027-02-15T00:00:00Z", OffsetDateTime.parse(input.getValue("releaseDate").jsonPrimitive.content).toInstant().toString())
            error("Version mismatch")
        })
        assertTrue(model.feedback.failed); assertEquals(7L, model.editing?.version); assertEquals("2027-02-15", model.dateDraft); assertEquals(" Draft notes ", model.descriptionDraft); assertEquals("v2", model.picker.pickVersionId)
    }

    @Test fun `saving details uses the returned revision and clears optional values without a follow up write`() = runTest {
        val model = editor(); var calls = 0
        model.save(" Renamed ", " ", "", "project", "v2", context { operation, variables ->
            calls++; assertEquals("WorkOpsUpdateRelease", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals("Renamed", input.getValue("name").jsonPrimitive.content); assertTrue(input["description"] == null || input["description"] == JsonNull); assertTrue(input["releaseDate"] == null || input["releaseDate"] == JsonNull)
            data("updateRelease", release(8, "Renamed"))
        })
        assertEquals(1, calls); assertFalse(model.feedback.failed); assertEquals(8L, model.editing?.version); assertEquals("Renamed", model.nameDraft)
        model.save("Retained", "Notes", "2027-02-30", "project", "v2", context { _, _ -> error("Must validate before writing") })
        assertTrue(model.feedback.failed); assertEquals("2027-02-30", model.dateDraft); assertEquals(8L, model.editing?.version)
    }

    @Test fun `denied bundle retains selection and a successful bundle remains excluded when refresh fails`() = runTest {
        val model = editor(); var bundleCalls = 0; var deny = true
        val ctx = context { operation, variables -> when (operation) {
            "WorkOpsBundleReleaseVersion" -> {
                bundleCalls++; assertEquals("release", variables.getValue("release").jsonPrimitive.content); assertEquals("project", variables.getValue("project").jsonPrimitive.content); assertEquals("v2", variables.getValue("version").jsonPrimitive.content)
                if (deny) error("Manage denied") else data("bundleVersion", JsonPrimitive(true))
            }
            else -> error("Refresh unavailable")
        } }
        model.add("Unsaved", "Retained", "2027-02-15", "project", "v2", ctx)
        assertTrue(model.feedback.failed); assertEquals("v2", model.picker.pickVersionId); assertEquals("Unsaved", model.nameDraft); assertTrue(model.components.isEmpty())
        deny = false; model.add(model.nameDraft, model.descriptionDraft, model.dateDraft, "project", "v2", ctx)
        assertFalse(model.feedback.failed); assertTrue(model.readFeedback.failed); assertEquals(listOf("v2"), model.components.map { it.versionId }); assertEquals(listOf("v2"), model.picker.excludedVersionIds)
        model.add(model.nameDraft, model.descriptionDraft, model.dateDraft, "project", "v2", ctx)
        assertEquals(2, bundleCalls); assertTrue(model.feedback.failed); assertEquals(7L, model.editing?.version)
    }

    @Test fun `confirmed removal keeps the captured target and does not repeat a successful write after failed readback`() = runTest {
        val model = editor().apply { components = listOf(WorkOpsReleaseComponentRecord("project", "v1", projectKey = "APP", projectName = "Application", versionName = "1.0")); picker.excludedVersionIds = listOf("v1") }
        model.beginRemove("v1", "Unsaved", "Draft", "2027-02-15", "project", "v2")
        model.remove(false, context { _, _ -> error("Unconfirmed") }); assertTrue(model.feedback.failed); assertNotNull(model.removing)
        var removals = 0
        model.remove(true, context { operation, variables ->
            if (operation == "WorkOpsUnbundleReleaseVersion") {
                removals++; assertEquals(setOf("release", "version"), variables.keys); assertEquals("v1", variables.getValue("version").jsonPrimitive.content); data("unbundleVersion", JsonPrimitive(true))
            } else error("Read unavailable")
        })
        assertEquals(1, removals); assertNull(model.removing); assertTrue(model.components.isEmpty()); assertFalse("v1" in model.picker.excludedVersionIds); assertTrue(model.readFeedback.failed); assertFalse(model.feedback.failed)
        assertEquals("Unsaved", model.nameDraft); assertEquals("v2", model.picker.pickVersionId); assertEquals(7L, model.editing?.version)
    }

    @Test fun `inline version errors preserve both the release draft and the independent version draft`() = runTest {
        val model = editor(); model.openVersion("Retained release", "Unsent description", "2027-02-15", "project")
        model.createVersion(" 3.0 ", "Version description", "2027-01-01", "2027-01-15", context { _, _ -> error("Version create denied") })
        assertTrue(model.picker.versionFeedback.failed); assertTrue(model.picker.creatingVersion); assertEquals(" 3.0 ", model.picker.versionDraft.name); assertEquals("2027-01-15", model.picker.versionDraft.releaseDate)
        assertEquals("Retained release", model.nameDraft); assertEquals("Unsent description", model.descriptionDraft); assertEquals("2027-02-15", model.dateDraft)
        model.cancelVersion(); assertFalse(model.picker.creatingVersion); assertEquals("Retained release", model.nameDraft)
    }

    @Test fun `delete requires confirmation retains a denied target and navigates only after the actual write succeeds`() = runTest {
        val model = editor(); model.beginDelete()
        model.delete(false, context { _, _ -> error("No unconfirmed API call") }); assertEquals("", model.nextHref); assertNotNull(model.deleting)
        model.delete(true, context { _, _ -> error("Manage denied") }); assertEquals("", model.nextHref); assertNotNull(model.deleting)
        model.delete(true, context { operation, variables -> assertEquals("WorkOpsDeleteRelease", operation); assertEquals(setOf("id"), variables.keys); data("deleteRelease", JsonPrimitive(true)) })
        assertEquals("/workops/releases?programId=program", model.nextHref); assertNull(model.release); assertNull(model.deleting); assertFalse(model.feedback.failed)
    }
}
