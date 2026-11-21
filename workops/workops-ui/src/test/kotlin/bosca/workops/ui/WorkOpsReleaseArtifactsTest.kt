package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsReleaseArtifactsTest {
    private fun json(value: String) = Json.parseToJsonElement(value)
    private fun data(namespace: String, field: String, value: JsonElement) = buildJsonObject { putJsonObject("workOps") { putJsonObject(namespace) { put(field, value) } } }
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun component(version: String, project: String = "project") = json("""{"releaseId":"release","projectId":"$project","versionId":"$version","deploymentOrder":0,"deploymentStatus":"PENDING","deployedAt":null,"rollbackVersionId":null}""")
    private fun publication(version: String = "v1", status: String = "PUBLISHED") = json("""{"id":"pub-$version","projectId":"project","versionId":"$version","artifactType":"DOCKER","namespace":"apps","coordinates":"image:1","environments":["testing"],"status":"$status"}""")
    private fun declaration() = json("""{"projectId":"project","type":"DOCKER","namespace":"apps","coordinate":"image:1","environments":["testing","production"]}""")
    private fun handler(versions: List<String> = listOf("v1"), fail: Set<String> = emptySet(), empty: Boolean = false): suspend (String, JsonObject) -> JsonElement = { operation, variables ->
        if (operation in fail) error("Unavailable $operation")
        when (operation) {
            "WorkOpsReleaseComponents" -> data("crossProject", "versionsForRelease", JsonArray(versions.map { component(it) }))
            "WorkOpsVersionProjects" -> data("projects", "all", json("""[{"id":"project","key":"APP","name":"Application"}]"""))
            "WorkOpsDeliveryVersions" -> data("versions", "byProject", JsonArray(versions.mapIndexed { index, version -> json("""{"id":"$version","name":"1.$index","description":null,"released":false,"archived":false,"startDate":null,"releaseDate":null,"sequenceNumber":$index,"version":0}""") }))
            "WorkOpsReleaseVersionArtifacts" -> data("multiRepo", "artifacts", JsonArray(if (empty) emptyList() else listOf(publication(variables.getValue("id").jsonPrimitive.content))))
            "WorkOpsReleaseDeclaredArtifacts" -> data("crossProject", "releaseDeclaredArtifacts", JsonArray(if (empty) emptyList() else listOf(declaration())))
            "WorkOpsReleaseRunStatuses" -> data("crossProject", "releaseRuns", json("""[{"runId":"run","status":"RUNNING","startedAt":"2027-01-01T00:00:00Z"}]"""))
            else -> error(operation)
        }
    }

    @Test fun `expected publications keep project type namespace and remaining environment scope`() {
        val base = WorkOpsReleaseArtifactRecord("project", "DOCKER", "apps", "image:1", listOf("testing", "production"))
        val model = WorkOpsReleaseArtifactsModel("release", declarations = listOf(base, base.copy(projectId = "other"), base.copy(type = "HELM"), base.copy(namespace = "other")), declarationsLoaded = true,
            publications = listOf(base.copy(publicationId = "publication", versionId = "version", environments = listOf("testing"), status = "PUBLISHED")))
        assertEquals(5, model.rows.size)
        assertEquals(listOf("production"), model.rows.first { it.publicationId.isEmpty() && it.identity == base.identity }.environments)
        model.publications = model.publications.map { it.copy(environments = emptyList()) }
        assertEquals(4, model.rows.size); assertTrue(model.rows.none { it.publicationId.isEmpty() && it.identity == base.identity })
        model.declarations = listOf(base.copy(environments = emptyList())); model.publications = model.publications.map { it.copy(environments = listOf("testing")) }
        assertEquals("All environments", model.rows.last().environmentLabel)
    }

    @Test fun `fresh reads use authorized labels and real run status`() = runTest {
        val model = WorkOpsReleaseArtifactsModel("release")
        model.refresh(context(handler()))
        assertTrue(model.componentsLoaded); assertTrue(model.declarationsLoaded); assertFalse(model.feedback.failed); assertTrue(model.needsPolling)
        assertEquals("APP · Application", model.projectLabel(model.rows.first())); assertEquals("1.0", model.versionLabel(model.rows.first()))
        assertEquals(listOf("PUBLISHED", "EXPECTED"), model.rows.map { it.status }); assertTrue(model.rows.none { it.stale }); assertFalse(model.verifiedEmpty)
    }

    @Test fun `failed publication read retains stale rows while removed bundles are pruned`() = runTest {
        val model = WorkOpsReleaseArtifactsModel("release"); model.refresh(context(handler(listOf("v1", "v2"))))
        model.refresh(context(handler(listOf("v2"), fail = setOf("WorkOpsReleaseVersionArtifacts", "WorkOpsReleaseDeclaredArtifacts", "WorkOpsReleaseRunStatuses"))))
        assertEquals(listOf("v2"), model.publications.map { it.versionId }); assertTrue(model.publications.single().stale)
        assertEquals(setOf("v2"), model.publicationErrors.keys); assertTrue(model.declarationFeedback.failed); assertFalse(model.declarationsLoaded)
        assertTrue(model.needsPolling); assertTrue(model.runFeedback.failed); assertFalse(model.verifiedEmpty)
        model.refresh(context(handler(listOf("v2"), empty = true)))
        assertTrue(model.verifiedEmpty); assertTrue(model.publicationErrors.isEmpty()); assertTrue(model.publications.isEmpty())
    }

    @Test fun `bundle failure cannot report verified empty and cancellation propagates`() = runTest {
        val model = WorkOpsReleaseArtifactsModel("release")
        model.refresh(context(handler(fail = setOf("WorkOpsReleaseComponents"))))
        assertTrue(model.feedback.failed); assertFalse(model.componentsLoaded); assertFalse(model.verifiedEmpty)
        assertFailsWith<CancellationException> { model.refresh(context { _, _ -> throw CancellationException("Navigation") }) }
    }
}
