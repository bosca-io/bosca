package bosca.artifacts.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ArtifactActionsTest {
    private val repository = ArtifactRepositoryView("repository", "namespace", "builds", "tool", "raw", "", "")
    private val version = ArtifactVersionView("version", "1.0", "", false, false, "", listOf(ArtifactBlobView("sha256:abc", "file", "tool.zip", "application/zip", 25)))
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun json(value: String): JsonElement = Json.parseToJsonElement(value)
    private fun emptyResponse(operation: String): JsonElement = when (operation) {
        "ArtifactsSecrets" -> json("""{"pipelines":{"secrets":[{"name":"registry-token"}]}}""")
        "ArtifactsPublicationDestinations" -> json("""{"artifactsAdmin":{"publicationDestinations":[]}}""")
        "ArtifactsRepository" -> json("""{"artifactsAdmin":{"repository":null}}""")
        else -> error("Unexpected operation $operation")
    }
    @Test fun `publication creation carries the immutable repository identity and selected secret`() = runTest {
        var input: JsonObject? = null
        val model = ArtifactPublicationModel(repository, editing = true)
        model.save("release", "owner", "tool", "123", "v", "registry-token", false, context { operation, variables ->
            if (operation == "ArtifactsCreatePublication") {
                input = variables.getValue("input").jsonObject
                json("""{"artifactsAdmin":{"createPublicationDestination":{"id":"destination"}}}""")
            } else emptyResponse(operation)
        })
        assertFalse(model.feedback.failed, model.feedback.message)
        val sent = assertNotNull(input)
        assertEquals("repository", sent.getValue("repositoryId").jsonPrimitive.content)
        assertEquals(123L, sent.getValue("githubRepositoryId").jsonPrimitive.long)
        assertEquals("v", sent.getValue("tagPrefix").jsonPrimitive.content)
        assertEquals("registry-token", sent.getValue("tokenSecretName").jsonPrimitive.content)
        assertFalse(sent["enabled"]?.jsonPrimitive?.boolean ?: false)
    }
    @Test fun `a stale sync save retains entered fields and reports the optimistic locking failure`() = runTest {
        var sent: JsonObject? = null
        val model = ArtifactSyncModel("repository", draft = ArtifactSyncDraft(id = "destination", version = 7), editing = true)
        model.save("renamed", "owner/image", "builder", "registry-token", true, context { operation, variables ->
            if (operation == "ArtifactsUpdateSync") { sent = variables; error("Destination version conflict") }
            else emptyResponse(operation)
        })
        assertTrue(model.feedback.failed)
        assertTrue(model.editing)
        assertEquals("renamed", model.draft.key)
        assertEquals(7L, assertNotNull(sent).getValue("version").jsonPrimitive.long)
        assertTrue("version conflict" in model.feedback.message)
    }
    @Test fun `completion submits the complete reviewed manifest and preserves failure for changed files`() = runTest {
        var sent: JsonObject? = null
        val model = ArtifactPublicationModel(repository, selectedVersionId = "version", reviewedVersion = version)
        model.complete("a".repeat(40), true, context { operation, variables ->
            assertEquals("ArtifactsCompleteVersion", operation)
            sent = variables
            error("The current manifest differs from the reviewed files")
        })
        val blob = assertNotNull(sent).getValue("blobs").jsonArray.single().jsonObject
        assertEquals("version", blob.getValue("versionId").jsonPrimitive.content)
        assertEquals("sha256:abc", blob.getValue("digest").jsonPrimitive.content)
        assertEquals("file", blob.getValue("role").jsonPrimitive.content)
        assertEquals("tool.zip", blob.getValue("filename").jsonPrimitive.content)
        assertTrue(model.feedback.failed)
        assertEquals(version, model.reviewedVersion)
        assertEquals("a".repeat(40), model.commitDraft)
    }
    @Test fun `missing confirmation invalid commit and incomplete publication never enqueue work`() = runTest {
        val ctx = context { _, _ -> error("No operation should execute") }
        val model = ArtifactPublicationModel(repository, selectedVersionId = "version", reviewedVersion = version)
        model.complete("a".repeat(40), false, ctx)
        assertTrue(model.feedback.failed)
        model.complete("short", true, ctx)
        assertTrue(model.feedback.failed)
        model.publish("destination", "a".repeat(40), ctx)
        assertTrue(model.feedback.failed)
        assertEquals("", model.feedback.runHref)
    }
    @Test fun `manual publication links the platform run returned under the callers authentication`() = runTest {
        val model = ArtifactPublicationModel(repository, selectedVersionId = "version", reviewedVersion = version.copy(completed = true))
        model.publish("destination", "a".repeat(40), context { operation, variables ->
            assertEquals("ArtifactsPublishRelease", operation)
            assertEquals("destination", variables.getValue("destinationId").jsonPrimitive.content)
            assertEquals("version", variables.getValue("versionId").jsonPrimitive.content)
            json("""{"artifactsAdmin":{"publishRelease":"platform-run"}}""")
        })
        assertFalse(model.feedback.failed)
        assertEquals("/artifacts/runs/platform-run", model.feedback.runHref)
    }
    @Test fun `cancellation is propagated rather than shown as an artifact action error`() = runTest {
        val feedback = ArtifactFeedback()
        assertFailsWith<CancellationException> { feedback.perform { throw CancellationException("cancelled") } }
        assertFalse(feedback.failed)
    }

    @Test fun `a failed publication retry does not link to an earlier successful queue request`() = runTest {
        val model = ArtifactPublicationModel(repository, selectedVersionId = "version", reviewedVersion = version.copy(completed = true))
        model.publish("destination", "a".repeat(40), context { _, _ ->
            json("""{"artifactsAdmin":{"publishRelease":"previous-run"}}""")
        })
        assertEquals("/artifacts/runs/previous-run", model.feedback.runHref)
        model.publish("destination", "a".repeat(40), context { _, _ -> error("Registry unavailable") })
        assertTrue(model.feedback.failed)
        assertEquals("Registry unavailable", model.feedback.message)
        assertEquals("", model.feedback.runHref)
    }

    @Test fun `upload target rejects a dot repository before sending a creation request`() = runTest {
        val ctx = context { _, _ -> error("A repository must not be created") }
        assertFailsWith<IllegalArgumentException> { ArtifactUploadTargets().resolve(ctx.gql, "new", "namespace", ".") }
    }

    @Test fun `paging publication versions clears the previous build commit and reviewed files`() = runTest {
        val commit = "b".repeat(40)
        val versionJson = """{"id":"next-version","repositoryId":"repository","version":"2.0","metadata":null,"created":"","completed":true,"finalized":false,"completedCommitSha":"$commit","blobs":[]}"""
        val model = ArtifactPublicationModel(repository, selectedVersionId = "version", reviewedVersion = version, commitDraft = "a".repeat(40))
        model.versionsPage(1, context { operation, variables ->
            when (operation) {
                "ArtifactsRepository" -> {
                    assertEquals(15L, variables.getValue("offset").jsonPrimitive.long)
                    json("""{"artifactsAdmin":{"repository":{"id":"repository","namespaceId":"namespace","name":"tool","type":"raw","created":"","modified":"","namespace":{"id":"namespace","name":"builds"},"versionCount":16,"tagCount":0,"versions":[$versionJson],"tags":[]}}}""")
                }
                "ArtifactsVersion" -> {
                    assertEquals("next-version", variables.getValue("id").jsonPrimitive.content)
                    json("""{"artifactsAdmin":{"version":$versionJson}}""")
                }
                "ArtifactsPublications" -> json("""{"artifactsAdmin":{"publications":[]}}""")
                else -> emptyResponse(operation)
            }
        })
        assertFalse(model.feedback.failed, model.feedback.message)
        assertEquals("next-version", model.selectedVersionId)
        assertEquals("", model.commitDraft)
        assertEquals(commit, model.reviewedVersion?.commitSha)
        assertEquals(emptyList(), model.reviewedVersion?.blobs)
    }
}
