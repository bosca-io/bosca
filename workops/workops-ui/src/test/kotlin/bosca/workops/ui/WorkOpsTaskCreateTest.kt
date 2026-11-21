package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.bml.render.encodeClientState
import bosca.bml.render.decodeClientState
import bosca.workops.ui.generated.WorkopsTaskCreateTaskCreateStateDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsTaskCreateTest {
    @Test fun `missing projects unavailable forms and non WorkOps forms retain the independent draft`() = runTest {
        val draft = buildJsonObject { put("summary", "Unsent task"); put("custom", false) }
        val removed = WorkOpsTaskCreateModel(projectId = "removed", draft = draft)
        removed.load(context { operation, _ ->
            assertEquals("WorkOpsTaskCreateChoices", operation)
            choices
        })
        assertEquals("The selected project is unavailable.", removed.error)
        assertEquals(draft, removed.draft)
        for ((response, message) in listOf(json("""{"formSchemas":{"byKey":null}}""") to "The task creation form is unavailable.",
            json(form.toString().replace("\"type\":\"WORK_OPS\"", "\"type\":\"SUBMISSION\"")) to "The project task form must have WorkOps type.")) {
            val model = WorkOpsTaskCreateModel(projectId = "project", draft = draft)
            model.load(context { operation, _ -> if (operation == "WorkOpsTaskCreateChoices") choices else response })
            assertEquals(message, model.error)
            assertEquals(draft, model.draft)
            assertNull(model.definition)
        }
    }

    @Test fun `static provider keys include only configured non-searching fields in the authored layout`() {
        val layout = json("""{"layout":[{"type":"section","children":[
          {"type":"field","control":"graphql-select","graphql":{}},
          {"type":"field","control":"graphql-select","graphql":{"searchVariable":"query"}},
          {"type":"field","control":"api-script-select","apiScript":{"searchParam":""}},
          {"type":"field","control":"api-script-select"},{"type":"field","control":"text-input"}]}]}""").jsonObject
        val definition = WorkOpsTaskForm("custom", "Custom", 1, buildJsonObject {}, layout)
        assertEquals("form-0-0 form-0-2", definition.staticChoiceKeys)
    }
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun json(source: String) = Json.parseToJsonElement(source)
    private val choices = json("""{"workOps":{"projects":{"all":[{"id":"project","key":"APP","name":"App","taskCreationFormSchemaKey":"app.create-task"}]},"tasks":{"taskTypes":[{"id":"type","name":"Task"}],"priorities":[{"id":"priority","name":"Medium","displayOrder":1,"colorHex":"#91a6f6"}],"resolutions":[]},"statuses":{"all":[{"id":"status","name":"To Do","category":"TODO"}]}}}""")
    private val form = json("""{"formSchemas":{"byKey":{"id":"form","key":"app.create-task","name":"App task","type":"WORK_OPS","version":3,"schema":{"type":"object","properties":{"projectId":{"type":"string"},"summary":{"type":"string"}},"required":["summary"]},"uiSchema":{"version":1,"layout":[{"type":"section","children":[{"type":"field","property":"projectId","control":"workops-select","workOpsEntity":"projects"},{"type":"field","property":"summary","control":"text-input"}]}]}}}}""")

    @Test fun `project selects its stored schema and domain choices without replacing custom layout`() = runTest {
        val model = WorkOpsTaskCreateModel(projectId = "project")
        model.begin(context { operation, variables -> when (operation) {
            "WorkOpsTaskCreateChoices" -> choices
            "WorkOpsTaskCreateForm" -> { assertEquals("app.create-task", variables.getValue("key").jsonPrimitive.content); form }
            else -> error(operation)
        } })
        assertTrue(model.open)
        assertEquals("app.create-task", model.definition?.key)
        assertEquals(3, model.definition?.version)
        assertEquals("section", model.definition?.uiSchema?.getValue("layout")?.jsonArray?.first()?.jsonObject?.getValue("type")?.jsonPrimitive?.content)
        assertEquals("APP · App", model.options.getValue("projectId").single().label)
        assertEquals(JsonPrimitive("project"), model.draft["projectId"])
        assertEquals("select", model.controlAliases["workops-select"])
        assertEquals("", model.error)
    }

    @Test fun `failed submission retains the full typed draft and successful retry creates one task`() = runTest {
        val model = WorkOpsTaskCreateModel(projectId = "project")
        val ctx = context { operation, _ -> when (operation) {
            "WorkOpsTaskCreateChoices" -> choices
            "WorkOpsTaskCreateForm" -> form
            else -> error(operation)
        } }
        model.begin(ctx)
        val draft = """{"projectId":"project","summary":"Retain this","customCount":0,"customEnabled":false,"optional":null}"""
        model.submit(draft, context { _, _ -> error("Create permission denied") })
        assertEquals(json(draft), model.draft)
        assertTrue(model.open)
        assertEquals("Create permission denied", model.error)
        var calls = 0
        model.submit(draft, context { operation, variables ->
            assertEquals("WorkOpsSubmitTaskForm", operation)
            assertEquals(json(draft), variables.getValue("attributes"))
            assertEquals("app.create-task", variables.getValue("key").jsonPrimitive.content)
            calls++
            json("""{"forms":{"submit":{"id":"created","type":"WORK_OPS"}}}""")
        })
        assertEquals(1, calls)
        assertEquals("created", model.createdId)
        assertFalse(model.open)
        model.begin(ctx)
        assertEquals("", model.createdId)
        assertEquals(buildJsonObject { put("projectId", "project") }, model.draft)
    }

    @Test fun `static choice sources use stored layout identities and leave searches dynamic`() {
        val ui = json("""{"version":1,"layout":[{"type":"section","children":[
            {"type":"field","property":"profile","control":"profile-select"},
            {"type":"field","property":"staticGraphql","control":"graphql-select","graphql":{"query":"query All"}},
            {"type":"field","property":"dynamicGraphql","control":"graphql-select","graphql":{"searchVariable":"q"}},
            {"type":"row","children":[
                {"type":"field","property":"staticScript","control":"api-script-select","apiScript":{"key":"static"}},
                {"type":"field","property":"dynamicScript","control":"api-script-select","apiScript":{"key":"dynamic","searchParam":"term"}}
            ]}
        ]}]}""").jsonObject
        val definition = WorkOpsTaskForm("custom", "Custom", 1, JsonObject(emptyMap()), ui)
        assertEquals("form-0-1 form-0-3-0", definition.staticChoiceKeys)
    }

    @Test fun `reloading a changed form retains the complete current draft`() = runTest {
        val model = WorkOpsTaskCreateModel(projectId = "project")
        val ctx = context { operation, _ -> when (operation) {
            "WorkOpsTaskCreateChoices" -> choices
            "WorkOpsTaskCreateForm" -> form
            else -> error(operation)
        } }
        model.begin(ctx)
        val draft = """{"projectId":"project","summary":"Retain this","attachment":"data:text/plain;base64,aGVsbG8=","optional":null}"""
        val previousKey = model.preserveKey
        model.reload(draft, context { operation, _ -> when (operation) {
            "WorkOpsTaskCreateChoices" -> choices
            "WorkOpsTaskCreateForm" -> JsonObject(form.jsonObject + ("formSchemas" to buildJsonObject {
                put("byKey", JsonObject(form.jsonObject.getValue("formSchemas").jsonObject.getValue("byKey").jsonObject + ("version" to JsonPrimitive(4))))
            }))
            else -> error(operation)
        } })
        assertEquals(4, model.definition?.version)
        assertEquals(json(draft), model.draft)
        assertTrue(model.open)
        assertEquals("", model.error)
        assertNotEquals(previousKey, model.preserveKey)
        val changedKey = model.preserveKey
        model.reload(draft, context { operation, _ -> when (operation) {
            "WorkOpsTaskCreateChoices" -> choices
            "WorkOpsTaskCreateForm" -> json(form.toString().replace("\"version\":3", "\"version\":4"))
            else -> error(operation)
        } })
        assertNotEquals(changedKey, model.preserveKey, "an explicit reload refreshes static sources even at the same schema version")
    }

    @Test fun `inaccessible project and cancellation do not become an available default form`() = runTest {
        val model = WorkOpsTaskCreateModel(projectId = "inaccessible")
        model.begin(context { operation, _ -> assertEquals("WorkOpsTaskCreateChoices", operation); choices })
        assertNull(model.definition)
        assertEquals("The selected project is unavailable.", model.error)
        assertFailsWith<CancellationException> {
            WorkOpsTaskCreateModel().begin(context { _, _ -> throw CancellationException("Stopped") })
        }
    }
    @Test fun `compiled task creation island switches stored forms and retains the complete draft`() = runTest {
        val model = WorkOpsTaskCreateModel(open = true, projectId = "project")
        val nextChoices = JsonObject(choices.jsonObject + ("workOps" to JsonObject(choices.jsonObject.getValue("workOps").jsonObject +
            ("projects" to json("""{"all":[{"id":"other","key":"NEXT","name":"Next project","taskCreationFormSchemaKey":"next.create-task"}]}""")))))
        val nextForm = JsonObject(form.jsonObject + ("formSchemas" to buildJsonObject {
            put("byKey", JsonObject(form.jsonObject.getValue("formSchemas").jsonObject.getValue("byKey").jsonObject +
                mapOf("key" to JsonPrimitive("next.create-task"), "version" to JsonPrimitive(7))))
        }))
        val ctx = RenderContext(renderLiveStateView = false, gql = context { operation, variables -> when (operation) {
            "WorkOpsTaskCreateChoices" -> nextChoices
            "WorkOpsTaskCreateForm" -> { assertEquals("next.create-task", variables.getValue("key").jsonPrimitive.content); nextForm }
            else -> error(operation)
        } }.gql)
        val draft = """{"projectId":"project","summary":"Retained summary","descriptionMarkdown":"Retained description","count":0,"enabled":false,"optional":null}"""
        val result = WorkopsTaskCreateTaskCreateStateDispatcher.dispatch(ctx, "changeProject", encodeClientState(model),
            buildJsonArray { add("other"); add(draft) }, "task-create")
        val changed = decodeClientState<WorkOpsTaskCreateModel>(requireNotNull(result.state))
        assertEquals("other", changed.projectId)
        assertEquals("next.create-task", changed.definition?.key); assertEquals(7, changed.definition?.version)
        assertEquals(JsonObject(json(draft).jsonObject + ("projectId" to JsonPrimitive("other"))), changed.draft)
        assertTrue(changed.open); assertEquals("", changed.error)
    }
}
