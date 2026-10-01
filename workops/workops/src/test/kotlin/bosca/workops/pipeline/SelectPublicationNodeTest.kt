package bosca.workops.pipeline

import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.workops.model.artifact.ArtifactPublication
import bosca.workops.model.artifact.ArtifactType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [SelectPublicationNode]: picks the publication of a declared artifact type from a version's set. */
class SelectPublicationNodeTest {

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(UUID::class, UUIDSerializer())
            contextual(java.time.OffsetDateTime::class, OffsetDateTimeSerializer())
        }
    }
    private val context = PipelineContext(AuthenticationContext(null, null), json)

    private fun publication(type: ArtifactType) = ArtifactPublication(
        id = UUID.random(), versionId = UUID.random(), projectId = UUID.random(),
        artifactType = type, coordinates = "c:${type.name}",
    )

    private fun inputs(vararg pubs: ArtifactPublication) = NodeInputs(
        mapOf("in" to PipelineValue.of(pubs.toList(), ListSerializer(ArtifactPublication.serializer()))),
    )

    @Test
    fun `selects the publication of the declared type from a mixed set`() = runTest {
        val docker = publication(ArtifactType.DOCKER)
        val out = SelectPublicationNode(id = "sel", artifactType = ArtifactType.DOCKER)
            .run(context, inputs(publication(ArtifactType.ANDROID_AAR), docker, publication(ArtifactType.MAVEN)))
        val selected = out.result?.encode(json)?.let { json.decodeFromJsonElement(ArtifactPublication.serializer(), it) }
        assertEquals(docker.id, selected?.id)
    }

    @Test
    fun `emits nothing when the version has no publication of the declared type`() = runTest {
        // The relay is a global pipeline: a project that publishes no iOS build has nothing to deploy
        // on the iOS channel — the select emits nothing and the downstream chain skips. (A DECLARED
        // artifact missing from the registry fails the CI job, not the relay.)
        val out = SelectPublicationNode(id = "sel", artifactType = ArtifactType.IOS_FRAMEWORK)
            .run(context, inputs(publication(ArtifactType.DOCKER)))
        assertEquals(null, (out as bosca.pipelines.node.NodeResult.Output).value)
    }
}
