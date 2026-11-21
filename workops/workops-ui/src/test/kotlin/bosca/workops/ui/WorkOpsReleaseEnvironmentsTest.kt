package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsReleaseEnvironmentsTest {
    @Test fun `environment badges distinguish verified sync failures drift deployment and unavailable reads`() {
        assertEquals("success", state(facts()).tone)
        assertEquals("neutral", state(facts(), loaded = false).tone)
        assertEquals("error", state(facts(targets = listOf(deployment(status = "FAILED")))).tone)
        assertEquals("info", state(facts(targets = listOf(deployment(status = "DEPLOYING")))).tone)
        assertEquals("warning", state(facts(targets = listOf(deployment(status = "ROLLED_BACK")))).tone)
    }
    private fun json(value: String) = Json.parseToJsonElement(value)
    private fun data(namespace: String, field: String, value: JsonElement) = buildJsonObject { putJsonObject("workOps") { putJsonObject(namespace) { put(field, value) } } }
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun environment(id: String = "development", sources: String = "[]") = json("""{"id":"$id","key":"$id","name":"$id","description":null,"displayOrder":0,"requiresApproval":false,"autoPromote":false,"typeId":"type","promotionSourceIds":$sources,"version":0,"permissions":[],"targetType":"GENERIC"}""")
    private fun deployment(project: String = "project", version: String = "v1", status: String = "DEPLOYED") = json("""{"id":"target-$project","projectId":"$project","versionId":"$version","targetKind":"HELM","status":"$status","deployedAt":"2027-01-01T00:00:00Z"}""")
    private fun facts(targets: List<JsonElement> = listOf(deployment()), drift: List<JsonElement> = emptyList(), compatible: List<String> = listOf("project")) = buildJsonObject {
        put("environmentState", JsonArray(targets)); put("environmentDrift", JsonArray(drift)); put("environmentCompatibleProjects", JsonArray(compatible.map(::JsonPrimitive)))
    }
    private fun state(value: JsonObject, loaded: Boolean = true): WorkOpsReleaseEnvironmentState = Json.decodeFromJsonElement(WorkOpsReleaseEnvironmentState.serializer(), buildJsonObject {
        put("deployments", value.getValue("environmentState")); put("drift", value.getValue("environmentDrift")); put("compatibleProjects", value.getValue("environmentCompatibleProjects")); put("loaded", loaded)
    })
    private fun handler(fail: String = "", facts: JsonObject = facts(), environments: List<JsonElement> = listOf(environment(), environment("production", "[\"development\"]"))): suspend (String, JsonObject) -> JsonElement = { operation, variables ->
        if (operation == fail) error("Unavailable $operation")
        when (operation) {
            "WorkOpsReleaseEnvironments" -> data("multiRepo", "environments", JsonArray(environments))
            "WorkOpsReleaseEnvironmentFacts" -> { assertEquals("release", variables.getValue("release").jsonPrimitive.content); buildJsonObject { putJsonObject("workOps") { put("multiRepo", facts) } } }
            "WorkOpsVersionProjects" -> data("projects", "all", json("""[{"id":"project","key":"APP","name":"Application"}]"""))
            "WorkOpsDeliveryVersions" -> {
                assertEquals("project", variables.getValue("project").jsonPrimitive.content)
                data("versions", "byProject", json("""[{"id":"v1","name":"1.0","description":null,"released":true,"archived":false,"startDate":null,"releaseDate":null,"sequenceNumber":1,"version":0},{"id":"v2","name":"2.0","description":null,"released":false,"archived":false,"startDate":null,"releaseDate":null,"sequenceNumber":2,"version":0}]"""))
            }
            else -> error(operation)
        }
    }

    @Test fun `deployment state never infers sync or no targets from failed or incomplete reads`() {
        assertEquals("In sync", state(facts()).status)
        assertEquals("Unavailable", state(facts(), false).status)
        assertEquals("No compatible projects", state(facts(compatible = emptyList())).status)
        assertEquals("No environment targets", state(facts(targets = emptyList())).status)
        assertEquals("Missing environment targets", state(facts(compatible = listOf("project", "missing"))).status)
        assertEquals("Failed", state(facts(targets = listOf(deployment(status = "FAILED")))).status)
        assertEquals("Rolled back", state(facts(targets = listOf(deployment(status = "ROLLED_BACK")))).status)
        assertEquals("Deploying", state(facts(targets = listOf(deployment(status = "DEPLOYING")))).status)
        val drift = json("""{"projectId":"project","deployedVersionId":"v1","expectedVersionId":"v2","driftType":"VERSION_MISMATCH"}""")
        val differs = state(facts(targets = listOf(deployment(), deployment("unrelated")), drift = listOf(drift)))
        assertEquals("Differs", differs.status); assertEquals(1, differs.deployedCount); assertEquals(1, differs.targets.size); assertEquals(1, differs.differences.size)
    }

    @Test fun `refresh retains selected family stop and resolves only authorized project version catalogs`() = runTest {
        val model = WorkOpsReleaseEnvironmentsModel("release", "program")
        model.refresh(context(handler(facts = facts(targets = listOf(deployment(), deployment("private", "hidden")), compatible = listOf("project", "private")))))
        assertTrue(model.loaded); assertFalse(model.feedback.failed); assertFalse(model.labelFeedback.failed)
        assertEquals("APP · Application", model.projectName("project")); assertEquals("1.0", model.versionName("v1"))
        assertEquals("Project unavailable", model.projectName("private")); assertEquals("Version unavailable", model.versionName("hidden")); assertEquals("", model.projectHref("private"))
        val family = model.families.single(); assertEquals("development", model.chosen(family).id)
        model.select(family.id, "production"); model.refresh(context(handler()))
        assertEquals("production", model.chosen(model.families.single()).id)
        assertEquals("development", model.sourceNames(model.chosen(model.families.single())))
        model.refresh(context(handler(environments = listOf(environment("a-new-entry"), environment("development", "[\"a-new-entry\"]"), environment("production", "[\"development\"]")))))
        assertEquals("production", model.chosen(model.families.single()).id, "A changed family identity retains its selected member.")
        assertFailsWith<IllegalArgumentException> { model.select(family.id, "outside") }
    }

    @Test fun `failed state reload preserves stale facts without a green status and empty environment reload prunes selections`() = runTest {
        val model = WorkOpsReleaseEnvironmentsModel("release", "program"); model.refresh(context(handler()))
        model.refresh(context(handler(fail = "WorkOpsReleaseEnvironmentFacts")))
        assertTrue(model.loaded); assertEquals(2, model.states.size)
        assertTrue(model.states.values.all { !it.loaded && it.targets.isNotEmpty() && it.status == "Unavailable" && it.error.isNotBlank() })
        model.refresh(context(handler(environments = emptyList())))
        assertTrue(model.loaded); assertTrue(model.environments.isEmpty()); assertTrue(model.states.isEmpty()); assertTrue(model.selected.isEmpty())
        assertTrue(model.projectNames.isEmpty()); assertFalse(model.labelFeedback.failed)
    }

    @Test fun `topology and label failures stay explicit and cancellation propagates`() = runTest {
        val model = WorkOpsReleaseEnvironmentsModel("release", "program"); model.refresh(context(handler()))
        model.refresh(context(handler(fail = "WorkOpsVersionProjects")))
        assertTrue(model.labelFeedback.failed); assertTrue(model.projectNames.isEmpty()); assertTrue(model.versionNames.isEmpty())
        model.refresh(context { _, _ -> error("Unavailable") })
        assertFalse(model.loaded); assertTrue(model.feedback.failed); assertTrue(model.states.values.all { !it.loaded && it.status == "Unavailable" })
        assertFailsWith<CancellationException> { model.refresh(context { _, _ -> throw CancellationException("Navigation") }) }
    }
}
