@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.ci.graphql

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.ArtifactDefinition
import bosca.git.model.PipelineJob
import bosca.graphql.server.ExecutableSchema
import bosca.graphql.server.GraphQL
import bosca.graphql.server.GraphQLContext
import bosca.graphql.server.GraphQLRequest
import bosca.graphql.server.runtimeWiring
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.mockk
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the actual job field SDL and generated job/artifact dispatchers. */
class GitPipelineJobArtifactsGraphQLTest {
    @AfterTest fun cleanup() = ProviderRegistry.clear()

    @Test fun `job artifacts expose typed fields and empty jobs return an empty list`() = runTest {
        ProviderRegistry.clear()
        provides<Json> { Json }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("job-artifacts-test") }
        val job = PipelineJob(pipelineRunId = UUID.random(), name = "publish", artifacts = listOf(
            ArtifactDefinition("raw", "builds", "cli:1.0.0", listOf("production", "staging")),
            ArtifactDefinition("maven", "libraries", "io.bosca:core:1.0.0"),
        ))
        val empty = PipelineJob(pipelineRunId = job.pipelineRunId, name = "test")
        val jobFields = GitPipelineJobControllerDispatcher(GitPipelineJobController(mockk(), mockk())).type.fieldResolvers
        val artifactFields = GitArtifactDefinitionControllerDispatcher(GitArtifactDefinitionController()).type.fieldResolvers
        val sdl = File("../git/src/main/resources/graphql/git.graphqls").readText()
        val jobType = Regex("(?ms)^type GitPipelineJob \\{.*?^}").find(sdl)?.value ?: error("Missing job SDL")
        val artifactsField = jobType.lineSequence().first { it.trimStart().startsWith("artifacts:") }
        val artifactType = Regex("(?ms)^type GitArtifactDefinition \\{.*?^}").find(sdl)?.value ?: error("Missing artifact SDL")
        val schema = ExecutableSchema.fromSdl("""
            type Query { job: GitPipelineJob! emptyJob: GitPipelineJob! }
            type GitPipelineJob { $artifactsField }
            $artifactType
        """.trimIndent(), runtimeWiring {
            type("Query") {
                field("job") { job }
                field("emptyJob") { empty }
            }
            type("GitPipelineJob") { field("artifacts", jobFields.getValue("artifacts")) }
            type("GitArtifactDefinition") { artifactFields.forEach { (name, resolver) -> field(name, resolver) } }
        })
        val result = GraphQL(schema).execute(GraphQLRequest(
            query = "{ job { artifacts { type namespace coordinate environments } } emptyJob { artifacts { coordinate } } }",
            context = GraphQLContext(mapOf("authenticationContext" to AuthenticationContext(null, null))),
        ))
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val data = result.data?.jsonObject ?: error("Missing GraphQL data")
        val returned = data.getValue("job").jsonObject.getValue("artifacts").jsonArray
        assertEquals(listOf("raw", "maven"), returned.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals("builds", returned.first().jsonObject.getValue("namespace").jsonPrimitive.content)
        assertEquals("cli:1.0.0", returned.first().jsonObject.getValue("coordinate").jsonPrimitive.content)
        assertEquals(listOf("production", "staging"), returned.first().jsonObject.getValue("environments").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonArray(emptyList()), returned.last().jsonObject.getValue("environments"))
        assertEquals(JsonArray(emptyList()), data.getValue("emptyJob").jsonObject.getValue("artifacts"))
    }
}
