package bosca.atlas.server

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File
import java.util.concurrent.TimeUnit
import bosca.documents.yjs.ProseMirrorYjsBridge
import yks.utils.Doc
import yks.utils.applyUpdate
import yks.utils.encodeStateAsUpdate
import kotlin.test.assertContentEquals

/** Exercises WorkOps through the production Atlas API and its imported BML application. */
class AtlasWorkOpsIntegrationTest {
    @Test
    fun `Atlas hosts WorkOps hierarchy tasks forms and content backed specifications`() = verify(false)

    @Test
    fun `standalone UI submits WorkOps forms with consumers in a separate worker`() = verify(true)

    private fun verify(separateWorker: Boolean) = AtlasHelmControllerFixture().use { controller ->
        AtlasStoreVendorFixture().use { store -> verify(separateWorker, controller, store) }
    }

    private fun verify(separateWorker: Boolean, controller: AtlasHelmControllerFixture, store: AtlasStoreVendorFixture) {
        // Media acceptance serves original bytes. Rendition processing has no image-processor
        // dependency in this fixture, so use the supported empty rendition configuration.
        AtlasProcessFixture(imageRenditions = false).use { fixture ->
            val (api, embeddedUi) = fixture.start("workops", (if (separateWorker) mapOf(
                "ATLAS_RUNNER_ENABLED" to "false", "ATLAS_UI_ENABLED" to "false",
            ) else mapOf("ATLAS_RUNNER_CONCURRENCY" to "16")) + mapOf("KUBERNETES_CONTROLLER_URL" to controller.origin, "ATLAS_STORE_VENDOR_URL" to store.origin),
                mainClass = if (java.lang.Boolean.getBoolean("atlas.workops.browser") && System.getProperty("atlas.workops.browser.scripts").orEmpty().let { it.isBlank() || it.split(',').any { script -> script in setOf("release-content", "release-health", "release-store", "release-live") } })
                    "bosca.atlas.server.AtlasReleaseNotesFixtureKt" else "bosca.atlas.server.ApplicationKt")
            val ui = if (separateWorker) fixture.startUi(api, fixture.newOrigin()) else embeddedUi
            fun JsonElement.at(vararg keys: String): JsonElement = keys.fold(this) { value, key -> value.jsonObject.getValue(key) }
            val token = fixture.graphql(api, """mutation { security { login { password(identifier: "admin", password: "atlas-admin-password") { token { token } } } } }""")
                .at("security", "login", "password", "token", "token").jsonPrimitive.content
            fun query(source: String) = fixture.graphql(ui, source, token)
            val profile = query("{ profiles { current { id } } }").at("profiles", "current").jsonArray.first().at("id").jsonPrimitive.content
            val creator = query("{ security { principals { current { id } } } }").at("security", "principals", "current", "id")
            assertTrue(creator.jsonPrimitive.content != profile)
            val portfolio = query("""mutation { workOps { portfolios { create(input: { key: "UI", name: "UI portfolio", ownerProfileId: "$profile" }) { id } } } }""")
                .at("workOps", "portfolios", "create", "id").jsonPrimitive.content
            val program = query("""mutation { workOps { programs { create(input: { portfolioId: "$portfolio", key: "UI", name: "UI program", ownerProfileId: "$profile" }) { id } } } }""")
                .at("workOps", "programs", "create", "id").jsonPrimitive.content
            val project = query("""mutation { workOps { projects { create(input: { programId: "$program", key: "UI", name: "UI project", ownerProfileId: "$profile" }) { id taskCreationFormSchemaKey } } } }""")
                .at("workOps", "projects", "create")
            val projectId = project.at("id").jsonPrimitive.content
            assertEquals("workops.create-task", project.at("taskCreationFormSchemaKey").jsonPrimitive.content)
            val form = query("""{ formSchemas { byKey(key: "workops.create-task") { key type schema uiSchema } } }""")
                .at("formSchemas", "byKey")
            assertEquals("WORK_OPS", form.at("type").jsonPrimitive.content)
            assertTrue("summary" in form.at("schema").jsonObject.getValue("properties").jsonObject)
            assertTrue(form.at("uiSchema").jsonObject.getValue("layout").jsonArray.isNotEmpty())
            val submitted = query("""mutation { forms { submit(input: { formSchemaKey: "workops.create-task", attributes: { projectId: "$projectId", summary: "Atlas form task", descriptionMarkdown: "A schema submitted task" } }) { id type } } }""")
                .at("forms", "submit")
            assertEquals("WORK_OPS", submitted.at("type").jsonPrimitive.content)
            fun submittedTask(id: String) = query("""{ workOps { tasks { task(id: "$id") { createdByPrincipalId reporterProfileId reporter { name } } } } }""")
                .at("workOps", "tasks", "task")
            val formTask = submittedTask(submitted.at("id").jsonPrimitive.content)
            assertEquals(creator, formTask.at("createdByPrincipalId"))
            assertEquals(profile, formTask.at("reporterProfileId").jsonPrimitive.content)
            val createPage = fixture.request(ui, "/workops/tasks?new=1&project=$projectId", token = token)
            assertEquals(200, createPage.statusCode(), createPage.body())
            assertTrue("data-bml-schema-form" in createPage.body(), createPage.body())
            val marker = requireNotNull(Regex("<script type=\"application/json\" data-bml-state-key=\"(workops-task-create.taskCreate[^\"]*)\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(createPage.body())) { createPage.body() }
            val createAction = fixture.request(ui, "/_bml/action", buildJsonObject {
                put("page", "/workops/tasks"); put("path", "/workops/tasks")
                put("stateKey", marker.groupValues[1]); put("state", marker.groupValues[2]); put("method", "submit")
                putJsonArray("args") { add(buildJsonObject { put("projectId", projectId); put("summary", "Atlas BML form task"); put("descriptionMarkdown", "Created through the real imported BML action") }.toString()) }
            }.toString(), token)
            assertEquals(200, createAction.statusCode(), createAction.body())
            val createdState = Json.parseToJsonElement(Json.parseToJsonElement(createAction.body()).at("state").jsonPrimitive.content)
            assertEquals("", createdState.at("error").jsonPrimitive.content, createdState.toString())
            assertTrue(createdState.at("createdId").jsonPrimitive.content.isNotBlank())
            val bmlTask = submittedTask(createdState.at("createdId").jsonPrimitive.content)
            assertEquals(creator, bmlTask.at("createdByPrincipalId"))
            assertEquals(profile, bmlTask.at("reporterProfileId").jsonPrimitive.content)
            query("""mutation { formSchemasMutation { save(input: {
              key: "atlas.public-intake", type: WORK_OPS, name: "Public intake", public: true,
              schema: { type: "object", properties: { summary: { type: "string" } }, required: ["summary"] },
              uiSchema: { version: 1, layout: [] }, configuration: { workOps: { projectId: "$projectId" } }
            }) { id } } }""")
            val anonymousSubmission = fixture.graphql(ui, """mutation { forms { submit(input: {
              formSchemaKey: "atlas.public-intake", attributes: { summary: "Public WorkOps intake" },
              profile: { name: "Anonymous WorkOps reporter", visibility: PUBLIC, attributes: [] }
            }) { id type } } }""").at("forms", "submit")
            assertEquals("WORK_OPS", anonymousSubmission.at("type").jsonPrimitive.content)
            val anonymousTask = submittedTask(anonymousSubmission.at("id").jsonPrimitive.content)
            assertEquals("00000000-0000-0000-0000-000000000000", anonymousTask.at("createdByPrincipalId").jsonPrimitive.content)
            assertTrue(anonymousTask.at("reporterProfileId").jsonPrimitive.content != profile)
            assertEquals("Anonymous WorkOps reporter", anonymousTask.at("reporter", "name").jsonPrimitive.content)
            val task = query("""mutation { workOps { tasks { create(input: { projectId: "$projectId", summary: "Atlas WorkOps integration task", descriptionMarkdown: "A real API-created task" }) { id key version createdByPrincipalId status { category } } } } }""")
                .at("workOps", "tasks", "create")
            assertEquals("TODO", task.at("status", "category").jsonPrimitive.content)
            val taskId = task.at("id").jsonPrimitive.content
            assertEquals(creator, task.at("createdByPrincipalId"))
            fun indexing() = query("{ scheduler { history(source: EVENT, offset: 0, limit: 200) { name status errorMessage definition } } }")
                .at("scheduler", "history").jsonArray.filter {
                    it.at("name").jsonPrimitive.contentOrNull == "task-index" &&
                        (it.at("definition") as? JsonObject)?.get("taskId")?.jsonPrimitive?.contentOrNull == taskId
                }
            if (separateWorker) {
                fixture.await("WorkOps indexing recorded while API consumers are disabled") { indexing().isNotEmpty() }
                assertTrue(indexing().all { it.at("status").jsonPrimitive.content == "PENDING" }, indexing().toString())
                assertTrue(query("{ workOps { notifications { mine(offset:0,limit:100) { taskId } } } }")
                    .at("workOps", "notifications", "mine").jsonArray.none { it.at("taskId").jsonPrimitive.contentOrNull == taskId })
                fixture.start("workops-worker", mapOf(
                    "ATLAS_API_ENABLED" to "false", "ATLAS_GIT_ENABLED" to "false",
                    "ATLAS_ARTIFACTS_ENABLED" to "false", "ATLAS_UI_ENABLED" to "false",
                    "ATLAS_RUNNER_QUEUES" to "workops,content,messages,index,common,pipelines,profile,git",
                    "ATLAS_SCHEDULER_ENABLED" to "false",
                    "ATLAS_RUNNER_CONCURRENCY" to "16",
                    "KUBERNETES_CONTROLLER_URL" to controller.origin,
                    "STORAGE_URL_PREFIX" to api, "STORAGE_URL_UPLOAD_PREFIX" to api,
                ))
            }
            fixture.await("WorkOps index executed by the selected worker role") {
                val executions = indexing()
                assertTrue(executions.none { it.at("status").jsonPrimitive.content == "FAILED" }, executions.toString())
                executions.any { it.at("status").jsonPrimitive.content == "COMPLETED" }
            }
            fixture.await("WorkOps notification delivered by the selected worker role") {
                query("{ workOps { notifications { mine(offset:0,limit:100) { taskId } } } }")
                    .at("workOps", "notifications", "mine").jsonArray.any { it.at("taskId").jsonPrimitive.contentOrNull == taskId }
            }
            val page = fixture.request(ui, "/workops/tasks", token = token)
            assertEquals(200, page.statusCode(), page.body())
            assertTrue("Atlas WorkOps integration task" in page.body(), page.body())
            assertTrue("Atlas form task" in page.body(), page.body())
            assertTrue("Atlas BML form task" in page.body(), page.body())
            assertTrue("href=\"/workops/tasks/${task.at("id").jsonPrimitive.content}\"" in page.body())
            fixture.graphql(api, """mutation { security { signup { passwordV2(identifier: "workops-reader@example.test", password: "reader-password", profile: { name: "WorkOps Reader", visibility: PUBLIC, attributes: [] }) { principal { id } } } } }""")
            val readerLogin = fixture.graphql(api, """mutation { security { login { password(identifier: "workops-reader@example.test", password: "reader-password") { principal { id } token { token } } } } }""")
                .at("security", "login", "password")
            val reader = readerLogin.at("token", "token").jsonPrimitive.content
            val readerIdentity = fixture.graphql(ui, "{ security { principals { current { groups { id type } } } } }", reader)
            val readerGroup = readerIdentity.at("security", "principals", "current", "groups").jsonArray
                .first { it.at("type").jsonPrimitive.content == "PRINCIPAL" }.at("id").jsonPrimitive.content
            assertEquals(JsonNull, fixture.graphql(ui, """{ workOps { tasks { task(id:"$taskId") { id } } } }""", reader).at("workOps", "tasks", "task"))
            for (action in listOf("VIEW", "EDIT")) query("""mutation { workOps { tasks { addPermission(id:"$taskId",groupId:"$readerGroup",action:$action) } } }""")
            assertEquals(task.at("id"), fixture.graphql(ui, """{ workOps { tasks { task(id:"$taskId") { id } } } }""", reader).at("workOps", "tasks", "task", "id"))
            val updated = fixture.graphql(ui, """mutation { workOps { tasks { update(id:"$taskId",input:{ summary:"Updated by permitted reader",startDate:"2026-11-01T23:45:00Z",expectedVersion:${task.at("version")} }) { version modifiedByPrincipalId } } } }""", reader)
                .at("workOps", "tasks", "update")
            assertEquals(readerLogin.at("principal", "id"), updated.at("modifiedByPrincipalId"))
            val stale = fixture.request(ui, "/graphql", buildJsonObject { put("query", """mutation { workOps { tasks { update(id:"$taskId",input:{ summary:"Stale edit",expectedVersion:${task.at("version")} }) { version } } } }""") }.toString(), reader)
            assertEquals(200, stale.statusCode(), stale.body())
            assertTrue(Json.parseToJsonElement(stale.body()).at("errors").jsonArray.any {
                it.at("path").jsonArray.map { part -> part.jsonPrimitive.content } == listOf("workOps", "tasks", "update")
            }, stale.body())
            assertTrue("Optimistic lock failed: Task $taskId" in fixture.logs(), fixture.logs())
            val retained = fixture.graphql(ui, """{ workOps { tasks { task(id:"$taskId") { summary startDate version } } } }""", reader).at("workOps", "tasks", "task")
            assertEquals("Updated by permitted reader", retained.at("summary").jsonPrimitive.content)
            assertEquals(java.time.Instant.parse("2026-11-01T23:45:00Z"), java.time.OffsetDateTime.parse(retained.at("startDate").jsonPrimitive.content).toInstant())
            assertEquals(updated.at("version"), retained.at("version"))
            val privateFilter = query("""mutation { workOps { savedFilters { create(input: { name: "Admin private filter", bqlSource: "ORDER BY modified DESC" }) { id version } } } }""")
                .at("workOps", "savedFilters", "create")
            val privateFilterId = privateFilter.at("id").jsonPrimitive.content
            assertEquals(JsonNull, fixture.graphql(ui, """{ workOps { savedFilters { savedFilter(id:"$privateFilterId") { id } } } }""", reader).at("workOps", "savedFilters", "savedFilter"))
            assertTrue(fixture.graphql(ui, "{ workOps { savedFilters { mine { id } } } }", reader).at("workOps", "savedFilters", "mine").jsonArray.isEmpty())
            val readerFilters = fixture.request(ui, "/workops/filters", token = reader)
            assertEquals(200, readerFilters.statusCode(), readerFilters.body())
            assertTrue("Admin private filter" !in readerFilters.body())
            val deniedFilterUpdate = fixture.request(ui, "/graphql", buildJsonObject { put("query", """mutation { workOps { savedFilters { update(id:"$privateFilterId",expectedVersion:${privateFilter.at("version")},input:{name:"Changed by reader",bqlSource:"ORDER BY modified DESC"}) { id } } } }""") }.toString(), reader)
            assertEquals(200, deniedFilterUpdate.statusCode(), deniedFilterUpdate.body())
            assertTrue(Json.parseToJsonElement(deniedFilterUpdate.body()).at("errors").jsonArray.any {
                it.at("extensions", "status").jsonPrimitive.int == 400 && it.at("message").jsonPrimitive.content == "Unauthorized access"
            }, deniedFilterUpdate.body())
            assertEquals("Admin private filter", query("""{ workOps { savedFilters { savedFilter(id:"$privateFilterId") { name } } } }""").at("workOps", "savedFilters", "savedFilter", "name").jsonPrimitive.content)
            val privateNotification = query("{ workOps { notifications { mine(offset:0,limit:1) { id readAt } } } }").at("workOps", "notifications", "mine").jsonArray.first()
            val privateNotificationId = privateNotification.at("id").jsonPrimitive.content
            assertEquals(JsonNull, privateNotification.at("readAt"))
            fixture.graphql(ui, """mutation { workOps { notifications { markRead(id:"$privateNotificationId") } } }""", reader)
            assertEquals(JsonNull, query("{ workOps { notifications { mine(offset:0,limit:200) { id readAt } } } }").at("workOps", "notifications", "mine").jsonArray.first { it.at("id").jsonPrimitive.content == privateNotificationId }.at("readAt"))
            val spec = query("""mutation { workOps { specs { create(input: { projectId: "$projectId", name: "Atlas document" }) { id metadataId metadata { id name document { title content } } } } } }""")
                .at("workOps", "specs", "create")
            assertEquals("Atlas document", spec.at("metadata", "name").jsonPrimitive.content)
            assertEquals(spec.at("metadataId"), spec.at("metadata", "id"))
            val doc = spec.at("metadata", "document")
            assertTrue(doc != JsonNull, spec.toString())
            val specMetadataId = spec.at("metadataId").jsonPrimitive.content
            assertEquals(spec.at("id"), query("""{ workOps { specs { specByMetadataId(metadataId:"$specMetadataId") { id } } } }""")
                .at("workOps", "specs", "specByMetadataId", "id"))
            assertEquals(JsonNull, fixture.graphql(ui, """{ workOps { specs { specByMetadataId(metadataId:"$specMetadataId") { id } } } }""", reader)
                .at("workOps", "specs", "specByMetadataId"))
            assertTrue(query("""{ workOps { savedFilters { validateSpecBql(source:"owner = currentUser()") { errors { message } } } } }""")
                .at("workOps", "savedFilters", "validateSpecBql", "errors").jsonArray.isEmpty())
            assertTrue(query("""{ workOps { savedFilters { validateBql(source:"owner = currentUser()") { errors { message } } } } }""")
                .at("workOps", "savedFilters", "validateBql", "errors").jsonArray.isNotEmpty())
            assertTrue(query("""mutation { content { metadata { setMetadataDocument(id:"$specMetadataId",version:1,document:{
                title:"Atlas document",content:{document:{type:"doc",attrs:{class:"article"},content:[
                  {type:"heading",attrs:{level:1},content:[{type:"text",text:"Atlas document"}]},
                  {type:"container",attrs:{name:"main",version:4,references:["GEN.1"]},content:[{type:"paragraph"}]},
                  {type:"bible",attrs:{usfm:["GEN.1.1"],metadataIds:["$specMetadataId"],metadataId:"content-bible",references:["JHN.3.16"]}}
                ]}}
            }) } } }""").at("content", "metadata", "setMetadataDocument").jsonPrimitive.boolean)
            val storedNodes = query("""{ content { metadata(id:"$specMetadataId",version:1) { document { content } } } }""")
                .at("content", "metadata", "document", "content", "document", "content").jsonArray
            assertEquals("article", query("""{ content { metadata(id:"$specMetadataId",version:1) { document { content } } } }""")
                .at("content", "metadata", "document", "content", "document", "attrs", "class").jsonPrimitive.content)
            assertEquals(4, storedNodes[1].at("attrs", "version").jsonPrimitive.int)
            assertEquals(listOf("GEN.1.1"), storedNodes[2].at("attrs", "usfm").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf(specMetadataId), storedNodes[2].at("attrs", "metadataIds").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("JHN.3.16"), storedNodes[2].at("attrs", "references").jsonArray.map { it.jsonPrimitive.content })
            val collaborationPath = "/api/v1/content/metadata/${spec.at("metadataId").jsonPrimitive.content}/document/collaboration?version=1"
            val update = fixture.upload(ui, collaborationPath, "\u0000\u0000", token)
            assertEquals(202, update.statusCode(), update.body())
            val collaboration = fixture.request(ui, collaborationPath, token = token)
            assertEquals(200, collaboration.statusCode(), collaboration.body())
            assertEquals("\u0000\u0000", collaboration.body())
            val deniedApi = fixture.request(api, collaborationPath)
            val deniedUi = fixture.request(ui, collaborationPath)
            assertTrue(deniedApi.statusCode() in 400..499, deniedApi.body())
            assertEquals(deniedApi.statusCode(), deniedUi.statusCode(), deniedUi.body())
            assertEquals(deniedApi.body(), deniedUi.body())
            assertTrue(deniedUi.body() != collaboration.body())
            val initialized = List(8) { index -> fixture.binaryRequest(if (index % 2 == 0) api else ui,
                collaborationPath, "POST", ByteArray(0), token) }.map { it.get(30, TimeUnit.SECONDS) }
            initialized.forEach {
                assertEquals(200, it.statusCode(), it.body().decodeToString())
                assertContentEquals(initialized.first().body(), it.body(), "Both HTTP hosts initialize identical CRDT items")
            }
            assertEquals(3, ProseMirrorYjsBridge.toContent(initialized.first().body()).document.content.size)
            val updates = List(8) { index ->
                val fork = Doc()
                val bytes = try {
                    applyUpdate(fork, initialized.first().body())
                    fork.getMap("attributes").set("http-writer-$index", index)
                    encodeStateAsUpdate(fork)
                } finally { fork.destroy() }
                fixture.binaryRequest(if (index % 2 == 0) api else ui, collaborationPath, "POST", bytes, token)
            }.map { it.get(30, TimeUnit.SECONDS) }
            updates.forEach { assertEquals(200, it.statusCode(), it.body().decodeToString()) }
            val durable = fixture.binaryRequest(ui, collaborationPath, "GET", ByteArray(0), token).get(30, TimeUnit.SECONDS)
            assertEquals(200, durable.statusCode(), durable.body().decodeToString())
            val storedCrdt = Doc()
            try {
                applyUpdate(storedCrdt, durable.body())
                repeat(8) { assertEquals(it.toLong(), (storedCrdt.getMap("attributes").get("http-writer-$it") as Number).toLong()) }
                assertEquals(3, storedCrdt.getXmlFragment("default").length)
            } finally { storedCrdt.destroy() }
            val deniedPost = fixture.binaryRequest(ui, collaborationPath, "POST", ByteArray(0), reader).get(30, TimeUnit.SECONDS)
            assertTrue(deniedPost.statusCode() in 400..499, deniedPost.body().decodeToString())
            val release = query("""mutation { workOps { crossProject { createRelease(input: { programId: "$program", name: "Atlas acceptance release", ownerProfileId: "$profile" }) { id name version } } } }""")
                .at("workOps", "crossProject", "createRelease")
            assertEquals("Atlas acceptance release", release.at("name").jsonPrimitive.content)
            val health = query("""{ analytics { counters { byId(id: "sessions.atlas-fixture") { id value(window: 2) values(window: 2) { time value } } } errors { groups(filter: { appId: "atlas-fixture", status: OPEN }, offset: 0, limit: 50) { total edges { fatal eventCount } } } } }""")
            assertEquals(0, health.at("analytics", "counters", "byId", "value").jsonPrimitive.int)
            assertEquals(2, health.at("analytics", "counters", "byId", "values").jsonArray.size)
            assertEquals(0, health.at("analytics", "errors", "groups", "total").jsonPrimitive.int)
            fixture.publishLiveSession("atlas-fixture", bosca.analytics.model.LiveSession("atlas-session-1", 40.7, -74.0, appVersion = "1.0"))
            fixture.publishLiveSession("atlas-fixture", bosca.analytics.model.LiveSession("other-version", 40.7, -74.0, appVersion = "2.0"))
            AtlasLogSubscription(ui, token, """subscription { liveSessions(appId: "atlas-fixture", appVersion: "1.0") { sessionId lat lon appId appVersion } }""").use { stream ->
                var received: JsonObject? = null
                fixture.await("retained release session through the UI subscription relay") { stream.poll()?.also { received = it } != null }
                val first = requireNotNull(received)
                assertEquals("next", first.at("type").jsonPrimitive.content, first.toString())
                assertEquals("atlas-session-1", first.at("payload", "data", "liveSessions", "sessionId").jsonPrimitive.content)
                fixture.publishLiveSession("atlas-fixture", bosca.analytics.model.LiveSession("atlas-session-live", 41.0, -73.0, appVersion = "1.0"))
                fixture.await("live release session through the UI subscription relay") { stream.poll()?.also { received = it } != null }
                assertEquals("atlas-session-live", requireNotNull(received).at("payload", "data", "liveSessions", "sessionId").jsonPrimitive.content)
            }
            assertTrue(fixture.request(ui, "/workops/tasks", token = null).statusCode() in listOf(302, 303))
            val lifecycleBoard = query("""mutation { workOps { boards { create(input:{projectId:"$projectId",name:"Lifecycle acceptance",type:SCRUM}) { id } } } }""")
                .at("workOps", "boards", "create", "id").jsonPrimitive.content
            val lifecycleSprint = query("""mutation { workOps { sprints { create(input:{boardId:"$lifecycleBoard",name:"Lifecycle iteration"}) { id version } } } }""")
                .at("workOps", "sprints", "create")
            val lifecycleSprintId = lifecycleSprint.at("id").jsonPrimitive.content
            val futureSprintId = query("""mutation { workOps { sprints { create(input:{boardId:"$lifecycleBoard",name:"Next lifecycle iteration"}) { id } } } }""")
                .at("workOps", "sprints", "create", "id").jsonPrimitive.content
            val lifecycleTaskId = query("""mutation { workOps { tasks { create(input:{projectId:"$projectId",summary:"Sprint lifecycle acceptance task"}) { id } } } }""")
                .at("workOps", "tasks", "create", "id").jsonPrimitive.content
            val startedSprint = query("""mutation { workOps { sprints { start(input:{sprintId:"$lifecycleSprintId",expectedVersion:${lifecycleSprint.at("version")},committedTaskIds:["$lifecycleTaskId"]}) { id state version committedTaskIds } } } }""")
                .at("workOps", "sprints", "start")
            assertEquals("ACTIVE", startedSprint.at("state").jsonPrimitive.content)
            assertEquals(lifecycleSprintId, query("""{ workOps { tasks { task(id:"$lifecycleTaskId") { sprintId } } } }""")
                .at("workOps", "tasks", "task", "sprintId").jsonPrimitive.content)
            val closedSprint = query("""mutation { workOps { sprints { close(input:{sprintId:"$lifecycleSprintId",expectedVersion:${startedSprint.at("version")},destinations:[{taskId:"$lifecycleTaskId",targetSprintId:"$futureSprintId"}]}) { state committedTaskIds } } } }""")
                .at("workOps", "sprints", "close")
            assertEquals("CLOSED", closedSprint.at("state").jsonPrimitive.content)
            assertEquals(startedSprint.at("committedTaskIds"), closedSprint.at("committedTaskIds"))
            val movedTask = query("""{ workOps { tasks { task(id:"$lifecycleTaskId") { sprintId modifiedByPrincipalId history(offset:0,limit:5) { changedByProfileId changes { fieldKey } } } } } }""")
                .at("workOps", "tasks", "task")
            assertEquals(futureSprintId, movedTask.at("sprintId").jsonPrimitive.content)
            assertEquals(creator, movedTask.at("modifiedByPrincipalId"))
            assertEquals(profile, movedTask.at("history").jsonArray.first().at("changedByProfileId").jsonPrimitive.content)
            assertTrue(movedTask.at("history").jsonArray.first().at("changes").jsonArray.any { it.at("fieldKey").jsonPrimitive.content == "sprint_id" })
            assertTrue(query("""{ workOps { sprints { sprint(id:"$futureSprintId") { committedTaskIds } } } }""")
                .at("workOps", "sprints", "sprint", "committedTaskIds").jsonArray.any { it.jsonPrimitive.content == lifecycleTaskId })

            if (System.getProperty("atlas.workops.browser") == "true") {
                val mentionMetadataId = query("""mutation { workOps { specs { create(input:{
                    projectId:"$projectId",name:"Atlas mention document"
                }) { metadataId } } } }""").at("workOps", "specs", "create", "metadataId").jsonPrimitive.content
                query("""mutation { content { metadata { setMetadataDocument(id:"$mentionMetadataId",version:1,document:{
                    title:"Atlas mention document",content:{document:{type:"doc",content:[
                        {type:"heading",attrs:{level:1},content:[{type:"text",text:"Atlas mention document"}]},
                        {type:"paragraph",content:[{type:"text",text:"A reference target"}]}
                    ]}}
                }) } } }""")
                val mentionCollectionId = query("""mutation { content { collection {
                    add(collection:{name:"Atlas mention collection"}) { id }
                } } }""").at("content", "collection", "add", "id").jsonPrimitive.content
                val mentionedProfile = query("""mutation { profiles { add(linkToPrincipal:false, profile:{
                    name:"Mention Candidate",visibility:PUBLIC,searchable:true,attributes:[]
                }) { id slug } } }""").at("profiles", "add")
                val mentionHandle = mentionedProfile.at("slug").jsonPrimitive.content
                assertTrue(mentionHandle.matches(Regex("[A-Za-z0-9._-]+")))
                query("""mutation { storageSystems { reindex(storageName:"Admin Search Index",
                    deleteFirst:false,metadata:true,collections:true,profiles:true) } }""")
                fun mentionResults(term: String, caller: String = token) = fixture.graphql(ui, """{
                    search { search(query:{query:${JsonPrimitive(term)},storageSystemName:"Admin Search Index",limit:20,offset:0}) {
                        documents { metadata { id name } collection { id name } profile { id name slug } }
                    } }
                }""", caller).at("search", "search", "documents").jsonArray
                fun JsonArray.hasEntity(type: String, id: String) = any { (it.jsonObject[type] as? JsonObject)?.get("id") == JsonPrimitive(id) }
                fixture.await("content and collection mention targets are indexed") {
                    mentionResults("Atlas mention document").hasEntity("metadata", mentionMetadataId) &&
                        mentionResults("Atlas mention collection").hasEntity("collection", mentionCollectionId)
                }
                assertFalse(mentionResults("Atlas mention document", reader).hasEntity("metadata", mentionMetadataId))
                assertFalse(mentionResults("Atlas mention collection", reader).hasEntity("collection", mentionCollectionId))
                query("""mutation { content {
                    metadata { addPermission(permission:{entityId:"$mentionMetadataId",groupId:"$readerGroup",action:VIEW}) { action } }
                    collection { addPermission(permission:{entityId:"$mentionCollectionId",groupId:"$readerGroup",action:VIEW}) { action } }
                } }""")
                assertTrue(mentionResults("Atlas mention document", reader).hasEntity("metadata", mentionMetadataId))
                assertTrue(mentionResults("Atlas mention collection", reader).hasEntity("collection", mentionCollectionId))
                fixture.await("authorized profile is available through the actual mention search index") {
                    query("""{ search { search(query:{query:"$mentionHandle",filter:["_type = \"profile\""],storageSystemName:"Admin Search Index",limit:20,offset:0}) { documents { profile { id } } } } }""")
                        .at("search", "search", "documents").jsonArray.any { document ->
                            (document.at("profile") as? JsonObject)?.get("id") == mentionedProfile.at("id")
                        }
                }
                val combinedMentions = mentionResults("mention")
                assertTrue(combinedMentions.hasEntity("metadata", mentionMetadataId), combinedMentions.toString())
                assertTrue(combinedMentions.hasEntity("collection", mentionCollectionId), combinedMentions.toString())
                val peerUi = fixture.startUi(api, fixture.newOrigin())
                query("""mutation { workOps { projects { create(input: { programId: "$program", key: "REL", name: "Atlas related project", ownerProfileId: "$profile" }) { id } } } }""")
                val directory = File(requireNotNull(System.getProperty("workspaceRoot")), "workops/workops-ui")
                val largeLog = File(fixture.directory, "workops-large-document-client.log")
                val largeClient = ProcessBuilder("node", "src/test/client/collaboration-large.mjs").directory(directory).apply {
                    environment()["WORKOPS_COLLABORATION_URL"] = ui.replaceFirst("http:", "ws:") + "/collaboration"
                    environment()["WORKOPS_COLLABORATION_PEER_URL"] = peerUi.replaceFirst("http:", "ws:") + "/collaboration"
                    environment()["WORKOPS_CONTENT_URL"] = ui
                    environment()["WORKOPS_COLLABORATION_ROOM"] = "$specMetadataId.1"
                    environment()["WORKOPS_COLLABORATION_TOKEN_A"] = token
                    environment()["WORKOPS_COLLABORATION_TOKEN_B"] = token
                    redirectErrorStream(true); redirectOutput(largeLog)
                }.start()
                try {
                    assertTrue(largeClient.waitFor(45, TimeUnit.SECONDS), "Large document check timed out: ${largeLog.readText()}")
                    assertEquals(0, largeClient.exitValue(), largeLog.readText() + "\n" + fixture.logs())
                } finally { if (largeClient.isAlive) largeClient.destroyForcibly() }
                val scripts = listOf("task-create", "task-detail", "task-labels", "requirements", "specifications", "specification-detail", "specification-delivery", "planning", "milestones", "projects", "boards", "sprints", "versions", "environments", "global-catalogs", "automation", "workflows", "sla", "components", "permissions", "notifications", "approvals", "task-descriptions", "task-history", "release-build", "release-ci", "release-content", "release-environments", "release-health", "release-store", "release-live", "task-search", "filters", "inbox", "page-layout", "empty-states", "toasts")
                val selectedScripts = System.getProperty("atlas.workops.browser.scripts").orEmpty()
                    .takeIf(String::isNotBlank)?.split(',') ?: scripts
                require(selectedScripts.all { it in scripts }) { "Unknown WorkOps browser script" }
                var referencedVersion = ""
                var archivedVersion = ""
                var slaPolicy = ""
                var notificationScheme = ""
                var unavailableNotificationScheme = ""
                var htmlTask = ""
                var markdownTask = ""
                var emptyTask = ""
                var historyTask = ""
                if ("task-history" in selectedScripts) {
                    historyTask = query("""mutation { workOps { tasks { create(input:{projectId:"$projectId",summary:"REQ UI-REQ-27",descriptionMarkdown:"Independent body"}) { id } } } }""")
                        .at("workOps", "tasks", "create", "id").jsonPrimitive.content
                }
                if ("task-descriptions" in selectedScripts) {
                    fun descriptionTask(name: String, markdown: String, dueDate: String? = null) = query("""mutation { workOps { tasks { create(input:{projectId:"$projectId",summary:"$name",descriptionMarkdown:"$markdown",dueDate:${JsonPrimitive(dueDate)}}) { id } } } }""")
                        .at("workOps", "tasks", "create", "id").jsonPrimitive.content
                    htmlTask = descriptionTask("Stored HTML description", "Independent Markdown seed", "2000-01-01T00:00:00Z")
                    markdownTask = descriptionTask("Markdown description", "## Markdown fallback", "2099-12-03T00:00:00Z")
                    emptyTask = descriptionTask("Empty description", "")
                    // descriptionHtml has no writer in the public task API. Seed only this disposable
                    // legacy record; all subsequent edits and readback use production APIs.
                    fixture.databaseConnection().use { connection ->
                        connection.prepareStatement("update workops.task set description_html = ? where id = ?::uuid").use {
                            it.setString(1, """<h2 id="description-note">Stored task description</h2><p>A <strong>prepared description</strong> with <em>formatting</em> &amp; a <a href="#description-note">local link</a>.</p><table><caption>Stored task values</caption><thead><tr><th>Name</th><th>Value</th></tr></thead><tbody><tr><td>Title</td><td>Example</td></tr></tbody></table><pre><code>Stored code &lt;sample&gt; &amp; retained whitespace</code></pre><img src="data:image/png;base64,AA==" alt="Stored illustration">""")
                            it.setString(2, htmlTask); assertEquals(1, it.executeUpdate())
                        }
                    }
                }
                if ("notifications" in selectedScripts) {
                    // Scheme authoring has no public API. Seed only this disposable database;
                    // preference changes below and in browsers use the existing caller-owned API.
                    fixture.databaseConnection().use { connection ->
                        connection.prepareStatement("insert into workops.notification_scheme(name,description,event_recipients) values (?,?,?::jsonb) returning id").use {
                            it.setString(1, "Atlas notification scheme"); it.setString(2, "Stored <notes> & recipients")
                            it.setString(3, """{"TASK_CREATED":[{"type":"Assignee"},{"type":"Group","groupId":"$readerGroup"}],"TASK_UPDATED":null,"LEGACY_EVENT":[{"type":"LegacyRecipient","stored":true}]}""")
                            it.executeQuery().use { row -> assertTrue(row.next()); notificationScheme = row.getString(1) }
                            it.setString(1, "Unavailable recipient data"); it.setString(2, "Malformed stored fixture"); it.setString(3, "[]")
                            it.executeQuery().use { row -> assertTrue(row.next()); unavailableNotificationScheme = row.getString(1) }
                        }
                    }
                }
                if ("sla" in selectedScripts) {
                    slaPolicy = query("""mutation { workOps { sla { createSlaPolicy(name:"Measured SLA policy",description:"Source detail fixture") { id } } } }""")
                        .at("workOps", "sla", "createSlaPolicy", "id").jsonPrimitive.content
                    // Goal authoring has no public API. Seed only the isolated fixture's policy;
                    // browser creation, access changes and restoration use production APIs.
                    fixture.databaseConnection().use { connection ->
                        connection.prepareStatement("""
                            insert into workops.sla_goal(policy_id,name,start_conditions,pause_conditions,stop_conditions,target_minutes,at_risk_at_percent,display_order)
                            values (?::uuid,?,'open',?,'done',?,?,?)
                        """.trimIndent()).use {
                            it.setString(1, slaPolicy)
                            for (index in 0..1) {
                                it.setString(2, if (index == 0) "Resolution" else "First response")
                                it.setString(3, if (index == 0) "paused" else null)
                                it.setInt(4, if (index == 0) 125 else 45)
                                it.setInt(5, if (index == 0) 90 else 75)
                                it.setInt(6, if (index == 0) 10 else 0)
                                assertEquals(1, it.executeUpdate())
                            }
                        }
                    }
                }
                if ("versions" in selectedScripts) {
                    referencedVersion = query("""mutation { workOps { versions { create(input:{projectId:"$projectId",name:"Fixture referenced version"}) { id } } } }""")
                        .at("workOps", "versions", "create", "id").jsonPrimitive.content
                    archivedVersion = query("""mutation { workOps { versions { create(input:{projectId:"$projectId",name:"Fixture archived version"}) { id } } } }""")
                        .at("workOps", "versions", "create", "id").jsonPrimitive.content
                    val referenceTask = query("""mutation { workOps { tasks { create(input:{projectId:"$projectId",summary:"Version reference fixture"}) { id } } } }""")
                        .at("workOps", "tasks", "create", "id").jsonPrimitive.content
                    // These stored fields have no mutation API. Seed only the disposable fixture;
                    // all browser actions and permission restoration still use the production API.
                    fixture.databaseConnection().use { connection ->
                        connection.prepareStatement("update workops.task set fix_version_ids = array[?::uuid] where id = ?::uuid").use {
                            it.setString(1, referencedVersion); it.setString(2, referenceTask); assertEquals(1, it.executeUpdate())
                        }
                        connection.prepareStatement("update workops.version set archived = true where id = ?::uuid").use {
                            it.setString(1, archivedVersion); assertEquals(1, it.executeUpdate())
                        }
                    }
                    val deletion = fixture.request(ui, "/graphql", buildJsonObject {
                        put("query", """mutation { workOps { versions { delete(id:"$referencedVersion") } } }""")
                    }.toString(), token)
                    val failure = Json.parseToJsonElement(deletion.body()).at("errors").jsonArray.single()
                    assertEquals("VERSION_IN_USE", failure.at("extensions", "code").jsonPrimitive.content)
                    assertEquals(400, failure.at("extensions", "status").jsonPrimitive.int)
                    assertEquals("This version is still referenced by tasks and cannot be deleted.", failure.at("message").jsonPrimitive.content)
                }
                for (script in selectedScripts) {
                    val readerPreferencesBefore = if (script == "inbox") fixture.graphql(ui, "{ workOps { notifications { myPreferences { version } } } }", reader)
                        .at("workOps", "notifications", "myPreferences") else JsonNull
                    if (script == "inbox") {
                        query("""mutation { workOps { notifications { updatePreferences(input: {
                          eventChannels: {}, watchAuthored: true, watchCommented: true, dailyDigest: false,
                          mutedTaskIds: [], mutedProjectIds: []
                        }) { version } } } }""")
                        repeat(35) { index ->
                            query("""mutation { workOps { tasks { create(input:{projectId:"$projectId",summary:"Inbox pagination $index"}) { id } } } }""")
                        }
                        fixture.await("inbox contains a second notification page") {
                            query("{ workOps { notifications { unreadCount } } }").at("workOps", "notifications", "unreadCount").jsonPrimitive.long >= 31
                        }
                        query("""mutation { workOps { notifications { updatePreferences(input: {
                          eventChannels: { LEGACY_EVENT: ["EMAIL"] }, watchAuthored: true, watchCommented: true, dailyDigest: false,
                          mutedTaskIds: ["$taskId"], mutedProjectIds: ["$projectId"]
                        }) { version } } } }""")
                    }
                    val log = File(fixture.directory, "workops-$script-browser.log")
                    val browser = ProcessBuilder("node", "src/test/browser/$script.mjs").directory(directory).apply {
                        environment()["ATLAS_WORKOPS_TEST_URL"] = ui
                        environment()["ATLAS_WORKOPS_TEST_API_URL"] = api
                        environment()["ATLAS_WORKOPS_TEST_PEER_URL"] = peerUi
                        environment()["ATLAS_WORKOPS_TEST_MENTION_HANDLE"] = mentionHandle
                        environment()["ATLAS_WORKOPS_TEST_MENTION_METADATA"] = mentionMetadataId
                        environment()["ATLAS_WORKOPS_TEST_MENTION_COLLECTION"] = mentionCollectionId
                        environment()["ATLAS_WORKOPS_TEST_PROJECT"] = projectId
                        environment()["ATLAS_WORKOPS_TEST_HELM_URL"] = controller.origin
                        environment()["ATLAS_WORKOPS_TEST_STORE_URL"] = store.origin
                        environment()["ATLAS_WORKOPS_TEST_TASK_KEY"] = task.at("key").jsonPrimitive.content
                        environment()["ATLAS_WORKOPS_TEST_PASSWORD"] = "atlas-admin-password"
                        environment()["ATLAS_WORKOPS_TEST_READER_GROUP"] = readerGroup
                        environment()["ATLAS_WORKOPS_TEST_READER_IDENTIFIER"] = "workops-reader@example.test"
                        environment()["ATLAS_WORKOPS_TEST_READER_PASSWORD"] = "reader-password"
                        environment()["ATLAS_WORKOPS_TEST_REFERENCED_VERSION"] = referencedVersion
                        environment()["ATLAS_WORKOPS_TEST_ARCHIVED_VERSION"] = archivedVersion
                        environment()["ATLAS_WORKOPS_TEST_SLA_POLICY"] = slaPolicy
                        environment()["ATLAS_WORKOPS_TEST_NOTIFICATION_SCHEME"] = notificationScheme
                        environment()["ATLAS_WORKOPS_TEST_UNAVAILABLE_NOTIFICATION_SCHEME"] = unavailableNotificationScheme
                        environment()["ATLAS_WORKOPS_TEST_HTML_TASK"] = htmlTask
                        environment()["ATLAS_WORKOPS_TEST_MARKDOWN_TASK"] = markdownTask
                        environment()["ATLAS_WORKOPS_TEST_EMPTY_TASK"] = emptyTask
                        environment()["ATLAS_WORKOPS_TEST_HISTORY_TASK"] = historyTask
                        redirectErrorStream(true); redirectOutput(log)
                    }.start()
                    try {
                        assertTrue(browser.waitFor(if (script in setOf("global-catalogs", "automation", "workflows", "sla", "permissions", "notifications", "approvals")) 240 else if (script in setOf("components", "task-descriptions", "task-history", "task-detail", "specification-detail", "specification-delivery", "planning", "projects", "boards", "sprints", "environments", "release-build", "release-ci", "release-content", "release-environments", "release-health", "release-store", "release-live")) 180 else 90, TimeUnit.SECONDS), "WorkOps browser timed out: ${log.readText()}")
                        if (browser.exitValue() != 0) {
                            val diagnostics = File(directory, "build/reports/atlas-workops-fixture/${java.net.URI(ui).port}/$script").apply { mkdirs() }
                            fixture.directory.listFiles().orEmpty().filter { it.isFile && it.extension == "log" }.forEach {
                                it.copyTo(File(diagnostics, it.name), overwrite = true)
                            }
                        }
                        assertEquals(0, browser.exitValue(), log.readText() + "\n" + fixture.logs())
                    } finally { if (browser.isAlive) browser.destroyForcibly() }
                    if (script == "inbox") {
                        val preferences = query("{ workOps { notifications { myPreferences { eventChannels watchAuthored dailyDigest mutedTaskIds mutedProjectIds dndStartLocal dndEndLocal } } } }")
                            .at("workOps", "notifications", "myPreferences")
                        assertEquals(JsonArray(emptyList()), preferences.at("eventChannels", "TASK_CREATED"))
                        assertEquals(JsonArray(listOf(JsonPrimitive("EMAIL"))), preferences.at("eventChannels", "LEGACY_EVENT"))
                        assertEquals(JsonArray(listOf(JsonPrimitive(taskId))), preferences.at("mutedTaskIds"))
                        assertEquals(JsonArray(listOf(JsonPrimitive(projectId))), preferences.at("mutedProjectIds"))
                        assertEquals(false, preferences.at("watchAuthored").jsonPrimitive.boolean)
                        assertEquals(true, preferences.at("dailyDigest").jsonPrimitive.boolean)
                        assertEquals("22:00", preferences.at("dndStartLocal").jsonPrimitive.content)
                        assertEquals("08:00", preferences.at("dndEndLocal").jsonPrimitive.content)
                        assertEquals(readerPreferencesBefore, fixture.graphql(ui, "{ workOps { notifications { myPreferences { version } } } }", reader).at("workOps", "notifications", "myPreferences"))
                    }
                }
            }
        }
    }
}
