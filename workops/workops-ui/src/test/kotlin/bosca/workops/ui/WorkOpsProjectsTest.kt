package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsProjectsTest {
    @Test fun `telemetry reads distinguish missing projects from available paged association lists`() = runTest {
        val invalid = WorkOpsProjectTelemetryModel("project", "unknown")
        invalid.retry("Retained", context { _, _ -> throw AssertionError("Unknown kind cannot fetch") })
        assertEquals("Unknown telemetry identifier kind.", invalid.feedback.message)
        for (kind in listOf("applications", "services")) {
            val unavailable = WorkOpsProjectTelemetryModel("project", kind, rows = listOf(WorkOpsProjectIdentifier("old", "Old")))
            unavailable.retry("Retained", context { _, _ -> json("""{"workOps":{"projects":{"project":null}}}""") })
            assertEquals("This project is unavailable.", unavailable.feedback.message)
            assertFalse(unavailable.loaded)
            assertEquals(emptyList(), unavailable.rows)
            val payload = buildJsonObject { putJsonObject("workOps") { putJsonObject("projects") { putJsonObject("project") {
                put("id", "project")
                put("analyticsApplications", JsonArray((0..51).map { buildJsonObject { put("id", "association-$it"); put("applicationId", "app-$it") } }))
                put("analyticsServices", JsonArray((0..51).map { buildJsonObject { put("id", "association-$it"); put("service", "service-$it") } }))
            } } } }
            for (target in listOf("association-26", "missing")) {
                val page = WorkOpsProjectTelemetryModel("project", kind, offset = 25, targetId = target, otherOffset = 50)
                page.retry("Retained", context { _, _ -> payload })
                assertFalse(page.feedback.failed, page.feedback.message)
                assertEquals(25L, page.offset)
                assertEquals(25, page.pageRows.size)
                assertEquals("association-25", page.pageRows.first().id)
                assertTrue(page.nextHref.contains("50"))
                assertEquals("Retained", page.draft)
            }
        }
    }

    @Test fun `dependency choices exclude self archived and already declared providers while retaining a disabled draft`() {
        val model = WorkOpsProjectDependenciesModel("project", dependencies = listOf(dependency(provider = "attached")), projects = listOf(
            WorkOpsPlanningRow(id = "project"), WorkOpsPlanningRow(id = "archived", archived = true),
            WorkOpsPlanningRow(id = "attached"), WorkOpsPlanningRow(id = "available", key = "LIB", name = "Library")))
        assertEquals(listOf("available"), model.providerChoices.map { it.value })
        model.providerDraft = "private"
        assertTrue(model.offeredProviders.single { it.value == "private" }.disabled)
        model.providerDraft = "available"
        assertEquals(1, model.offeredProviders.size)
        assertEquals("Project unavailable", model.projectLabel("private"))
        assertEquals("LIB · Library", model.projectLabel("available"))
    }
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun json(value: String) = Json.parseToJsonElement(value)
    private fun data(namespace: String, field: String, value: JsonElement) = buildJsonObject {
        putJsonObject("workOps") { putJsonObject(namespace) { put(field, value) } }
    }
    private fun project(version: Long = 7) = WorkOpsProjectRecord("project", "PLAN", "Release project", "Details", "private-owner", "",
        "private-program", false, "2026-11-01T00:00:00Z", "2026-11-02T00:00:00Z", version)
    private fun destination() = WorkOpsPlanningRow(id = "destination", key = "NEXT", name = "Next program")
    private fun version(id: String = "version", revision: Long = 7) = json("""{"id":"$id","name":"Iteration $id","description":null,"released":false,"archived":false,"startDate":null,"releaseDate":null,"sequenceNumber":1,"version":$revision}""")
    private fun catalogs(versions: List<JsonElement> = emptyList(), components: List<JsonElement> = emptyList(), labels: List<JsonElement> = emptyList()) =
        buildJsonObject { putJsonObject("workOps") {
            putJsonObject("versions") { put("byProject", JsonArray(versions)) }
            putJsonObject("components") { put("byProject", JsonArray(components)) }
            putJsonObject("labels") { put("byProject", JsonArray(labels)) }
        } }
    private fun dependency(id: String = "declaration", provider: String = "provider") = WorkOpsProjectDependencyRecord(id, "project", provider, "*", "BUILD", "CURRENT", 3)
    private fun dependencyJson(row: WorkOpsProjectDependencyRecord) = buildJsonObject {
        put("id", row.id); put("consumerProjectId", row.consumerId); put("providerProjectId", row.providerId)
        put("providerVersionConstraint", row.constraint); put("dependencyType", row.type); put("status", row.status); put("version", row.version)
    }
    private fun dependencies(rows: List<WorkOpsProjectDependencyRecord> = emptyList()) = buildJsonObject { putJsonObject("workOps") {
        putJsonObject("projects") { put("all", json("""[{"id":"provider","key":"API","name":"API project","archivedAt":null}]""")) }
        putJsonObject("multiRepo") { put("dependencies", JsonArray(rows.map(::dependencyJson))); put("consumers", JsonArray(emptyList())) }
    } }
    private fun repositories(association: String = "association", visible: Boolean = true) = json("""{
        "workOps":{"projects":{"project":{"id":"project","repositories":[{"id":"$association","repositoryId":"repository"}]}}},
        "git":{"repositories":${if (visible) """[{"id":"repository","name":"API repository","slug":"api","archived":false}]""" else "[]"}},
        "profiles":{"current":[]}
    }""")

    @Test fun `repository reload filters attached and archived choices and preserves an initialized human draft`() = runTest {
        val payload = json("""{
          "workOps":{"projects":{"project":{"id":"project","repositories":[{"id":"association","repositoryId":"attached"}]}}},
          "git":{"repositories":[{"id":"attached","name":"Attached","slug":"attached","archived":false},
            {"id":"free","name":"Available","slug":"available","archived":false},{"id":"archived","name":"Archived","slug":"archived","archived":true}]},
          "profiles":{"current":[{"id":"human","name":" Human Author ","isPrimary":false,"attributes":[{"typeId":"bosca.profiles.email","attributes":{"email":3}}]}]}
        }""")
        val model = WorkOpsProjectRepositoriesModel("project", targetId = "absent")
        model.reload(context { _, _ -> payload })
        assertEquals(listOf("free"), model.choices.map { it.value })
        assertEquals("Human Author", model.authorNameDraft)
        assertEquals("", model.authorEmailDraft)
        model.authorNameDraft = "Retained human"
        model.authorEmailDraft = "human@example.test"
        model.reload(context { _, _ -> payload })
        assertEquals("Retained human", model.authorNameDraft)
        assertEquals("human@example.test", model.authorEmailDraft)
        model.retry("draft", context { _, _ -> json("""{"workOps":{"projects":{"project":null}},"git":{"repositories":[]},"profiles":{"current":null}}""") })
        assertFalse(model.loaded)
        assertEquals(emptyList(), model.rows)
        assertEquals(emptyList(), model.choices)
    }

    @Test fun `project moves retain the inspected version and selected program after choices reload or rejection`() = runTest {
        val model = WorkOpsProjectDetailModel("project", project = project(), programs = listOf(destination()))
        assertEquals("Profile unavailable", model.project?.ownerLabel); assertEquals("Program unavailable", model.programName)
        model.beginMove(); model.project = project(9)
        model.reloadMoveChoices("destination", context { operation, _ ->
            assertEquals("WorkOpsPlanningProgramChoices", operation)
            data("programs", "all", json("""[{"id":"destination","key":"NEXT","name":"Next program","archivedAt":null}]"""))
        })
        model.move("destination", context { operation, variables ->
            assertEquals("WorkOpsMoveProject", operation); assertEquals(7L, variables.getValue("version").jsonPrimitive.long)
            assertEquals("destination", variables.getValue("program").jsonPrimitive.content); error("Newer project")
        })
        assertTrue(model.feedback.failed); assertEquals(7L, model.moveTarget?.version); assertEquals("destination", model.moveProgramId)
    }

    @Test fun `successful moves retain their result when the subsequent project read fails`() = runTest {
        val model = WorkOpsProjectDetailModel("project", project = project(), programs = listOf(destination()))
        model.beginMove()
        model.move("destination", context { operation, _ ->
            if (operation == "WorkOpsMoveProject") data("projects", "move", json("""{"id":"project"}""")) else error("Read unavailable")
        })
        assertTrue(model.feedback.failed); assertTrue(model.moved); assertNull(model.moveTarget)
        assertEquals("/workops/projects/project", model.href)
    }

    @Test fun `version release keeps its inspected version and confirmation after a rejected write`() = runTest {
        val original = WorkOpsProjectCatalogRow("version", "1.2.3", version = 7)
        val model = WorkOpsProjectCatalogModel("project", "versions", rows = listOf(original), loaded = true)
        model.beginRelease(original.id); model.rows = listOf(original.copy(version = 9))
        model.release(false, context { _, _ -> error("Must not write") })
        assertEquals("Confirm the release.", model.feedback.message)
        model.release(true, context { operation, variables ->
            assertEquals("WorkOpsReleaseProjectVersion", operation); assertEquals(7L, variables.getValue("version").jsonPrimitive.long)
            error("Newer version")
        })
        assertTrue(model.feedback.failed); assertEquals(original, model.releaseTarget)
    }

    @Test fun `created catalogs retain a result link after a failed read and resolve off page results`() = runTest {
        val model = WorkOpsProjectCatalogModel("project", "versions", loaded = true).apply { open() }
        model.create("1.2.3", "Notes", "", "", "", "", context { operation, _ ->
            if (operation == "WorkOpsCreateProjectVersion") data("versions", "create", version("created")) else error("Read unavailable")
        })
        assertTrue(model.feedback.failed); assertFalse(model.creating); assertFalse(model.loaded)
        assertTrue("item=created#workops-project-versions-created" in model.resultHref)
        val later = WorkOpsProjectCatalogModel("project", "versions", targetId = "version-26")
        later.reload(context { _, _ -> catalogs((0..26).map { version("version-$it") }) })
        assertEquals(25L, later.offset); assertEquals(2, later.pageRows.size)
        assertTrue(later.pageRows.any { it.id == "version-26" }); assertTrue("tab=versions&offset=0" in later.previousHref)
    }

    @Test fun `component creation preserves the assignee mode and cancellation propagates`() = runTest {
        val model = WorkOpsProjectCatalogModel("project", "components", loaded = true).apply { open() }
        model.create("API", "Retained details", "COMPONENT_LEAD", "", "", "", context { operation, variables ->
            assertEquals("WorkOpsCreateProjectComponent", operation)
            assertEquals("COMPONENT_LEAD", variables.getValue("input").jsonObject.getValue("assigneeMode").jsonPrimitive.content)
            error("Denied")
        })
        assertTrue(model.feedback.failed); assertTrue(model.creating)
        assertEquals("COMPONENT_LEAD", model.modeDraft); assertEquals("Retained details", model.descriptionDraft)
        assertFailsWith<CancellationException> { model.reloadDraft("API draft", "Notes", "UNASSIGNED", "", "", "", context { _, _ -> throw CancellationException("Left project") }) }
        assertEquals("API draft", model.nameDraft)
    }

    @Test fun `project labels retain rejected names and colors and existing deletion contracts send only the id`() = runTest {
        val label = WorkOpsProjectCatalogModel("project", "labels", loaded = true).apply { open() }
        label.create("Bad label", "", "", "#123456", "", "", context { operation, variables ->
            assertEquals("WorkOpsCreateProjectLabel", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals("PROJECT", input.getValue("scope").jsonPrimitive.content); assertEquals("project", input.getValue("projectId").jsonPrimitive.content)
            error("Invalid label")
        })
        assertEquals("Bad label", label.nameDraft); assertEquals("#123456", label.colorDraft); assertTrue(label.creating)
        for (kind in listOf("components", "labels")) {
            val row = WorkOpsProjectCatalogRow("item", "API", version = 8)
            val model = WorkOpsProjectCatalogModel("project", kind, rows = listOf(row), loaded = true)
            model.beginDelete(row.id)
            model.delete(false, context { _, _ -> error("Must not write") }); assertEquals("Confirm deletion.", model.feedback.message)
            model.delete(true, context { _, variables -> assertEquals(setOf("id"), variables.keys); error("Denied") })
            assertEquals(row, model.deleteTarget); assertTrue(model.feedback.failed)
        }
    }

    @Test fun `dependency choices preserve unavailable selections and both offset directions`() = runTest {
        val model = WorkOpsProjectDependenciesModel("project", consumersOffset = 50, targetId = "dependency-26")
        model.reloadChoices("missing", "RUNTIME", "6.0.*", context { _, _ -> dependencies((0..26).map { dependency("dependency-$it", "hidden-$it") }) })
        assertEquals(25L, model.offset); assertEquals(2, model.pageRows.size)
        assertTrue("consumersOffset=50" in model.previousHref); assertTrue("offset=25&consumersOffset=25" in model.previousConsumersHref)
        assertEquals("Project unavailable", model.projectLabel("hidden-1"))
        assertEquals("RUNTIME", model.typeDraft); assertEquals("6.0.*", model.constraintDraft)
        assertTrue(model.offeredProviders.single { it.value == "missing" }.disabled)
        model.creating = true
        model.create("provider", "CONTRACT", "", context { operation, variables ->
            assertEquals("WorkOpsDeclareProjectDependency", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals("project", input.getValue("consumerProjectId").jsonPrimitive.content)
            assertEquals("*", input.getValue("providerVersionConstraint").jsonPrimitive.content)
            assertEquals("CONTRACT", input.getValue("dependencyType").jsonPrimitive.content); error("Denied")
        })
        assertTrue(model.feedback.failed); assertEquals("provider", model.providerDraft); assertEquals("", model.constraintDraft)
    }

    @Test fun `declared dependencies keep their result when refreshing fails`() = runTest {
        val model = WorkOpsProjectDependenciesModel("project", loaded = true, creating = true,
            projects = listOf(WorkOpsPlanningRow(id = "provider", key = "API", name = "API")))
        model.create("provider", "BUILD", "*", context { operation, _ ->
            if (operation == "WorkOpsDeclareProjectDependency") data("multiRepo", "declareDependency", dependencyJson(dependency())) else error("Read unavailable")
        })
        assertFalse(model.creating); assertFalse(model.loaded); assertTrue(model.feedback.failed)
        assertTrue("item=declaration#workops-dependency-declaration" in model.resultHref)
    }

    @Test fun `analytics paging keeps the other list page and rejected edits retain their draft`() = runTest {
        val model = WorkOpsProjectTelemetryModel("project", "applications", offset = 25, otherOffset = 50,
            rows = (0..60).map { WorkOpsProjectIdentifier("app-$it", "app.$it") }, loaded = true)
        assertTrue("applicationOffset=50&serviceOffset=50" in model.nextHref)
        model.add(" mobile.app ", context { operation, variables ->
            assertEquals("WorkOpsAddProjectApplication", operation); assertEquals("mobile.app", variables.getValue("value").jsonPrimitive.content); error("Denied")
        })
        assertEquals(" mobile.app ", model.draft); assertTrue(model.feedback.failed)
        model.beginDelete("app-1", "Unsent value")
        model.delete(false, context { _, _ -> error("Must not write") })
        assertEquals("Unsent value", model.draft); assertEquals("app-1", model.deleteTarget?.id)
        model.retry("New retained value", context { _, _ -> error("Read unavailable") })
        assertFalse(model.loaded); assertEquals("New retained value", model.draft)
    }

    @Test fun `both analytics kinds retain successful additions across a failed read`() = runTest {
        for (kind in listOf("applications", "services")) {
            val model = WorkOpsProjectTelemetryModel("project", kind, loaded = true)
            model.add("service.app", context { operation, variables ->
                when (operation) {
                    "WorkOpsAddProjectApplication" -> {
                        assertEquals("project", variables.getValue("project").jsonPrimitive.content)
                        data("projects", "addAnalyticsApplication", json("""{"id":"added","applicationId":"service.app"}"""))
                    }
                    "WorkOpsAddProjectService" -> {
                        assertEquals("project", variables.getValue("project").jsonPrimitive.content)
                        data("projects", "addAnalyticsService", json("""{"id":"added","service":"service.app"}"""))
                    }
                    else -> error("Read unavailable")
                }
            })
            assertTrue(model.feedback.failed); assertEquals("", model.draft); assertFalse(model.loaded)
            assertTrue("kind=$kind&item=added" in model.resultHref)
        }
    }

    @Test fun `repository setup retains author drafts and rejects changed associations before committing`() = runTest {
        val row = WorkOpsProjectRepositoryRecord("association", "repository", "API repository")
        val model = WorkOpsProjectRepositoriesModel("project", rows = listOf(row), loaded = true)
        model.beginSetup(row.id, "Unsent repository")
        model.generate("Human author", "human@example.test", context { operation, _ ->
            assertEquals("WorkOpsProjectRepositories", operation); repositories("replacement-association")
        })
        assertTrue(model.feedback.failed); assertEquals(row, model.setupTarget)
        assertEquals("Human author", model.authorNameDraft); assertEquals("human@example.test", model.authorEmailDraft)
        assertEquals("Unsent repository", model.repositoryDraft); assertNull(model.generated)
    }

    @Test fun `a stored repository association keeps its WorkOps setup behavior when Git labels are private`() = runTest {
        val row = WorkOpsProjectRepositoryRecord("association", "repository", available = false)
        val model = WorkOpsProjectRepositoriesModel("project", rows = listOf(row), loaded = true)
        model.beginSetup(row.id, "")
        model.generate(" Human author ", "human@example.test", context { operation, variables -> when (operation) {
            "WorkOpsProjectRepositories" -> repositories(visible = false)
            "WorkOpsCreateProjectDeployConfig" -> {
                assertEquals("Human author", variables.getValue("name").jsonPrimitive.content)
                assertEquals("human@example.test", variables.getValue("email").jsonPrimitive.content)
                data("multiRepo", "createProjectDeployConfig", json("""{"repositoryId":"repository","path":".bosca/deploy.yaml","branch":"main","commitSha":"abc123","content":"environments: {}"}"""))
            }
            else -> error(operation)
        } })
        assertFalse(model.feedback.failed); assertNull(model.setupTarget); assertEquals("Repository unavailable", model.generated?.repositoryName)
        assertEquals("main", model.generated?.branch); assertEquals("", row.href)
    }

    @Test fun `repository attachment success retains an off page result through a failed reload`() = runTest {
        val model = WorkOpsProjectRepositoriesModel("project", choices = listOf(bosca.design.model.SchemaFormOption("repository", "API")), loaded = true)
        model.attach("repository", context { operation, _ ->
            if (operation == "WorkOpsAttachProjectRepository") data("projects", "addProjectRepository", json("""{"id":"association","repositoryId":"repository"}""")) else error("Read unavailable")
        })
        assertTrue(model.feedback.failed); assertFalse(model.loaded); assertEquals("", model.repositoryDraft)
        assertTrue("item=association#workops-project-repository-association" in model.resultHref)
    }
}
