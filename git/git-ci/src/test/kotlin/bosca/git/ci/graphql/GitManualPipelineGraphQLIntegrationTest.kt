@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.ci.graphql

import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.asProvider
import bosca.di.provides
import bosca.git.ci.parser.ExpressionContext
import bosca.git.ci.parser.PipelineExpressionParser
import bosca.git.ci.parser.PipelineYamlParser
import bosca.git.ci.repository.PipelineRunRepository
import bosca.git.ci.service.PipelineRunServiceImpl
import bosca.git.model.Pipeline
import bosca.git.model.PipelineRun
import bosca.git.model.Repository
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineService
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryService
import bosca.graphql.server.ExecutableSchema
import bosca.graphql.server.ExtendedScalars
import bosca.graphql.server.GraphQL
import bosca.graphql.server.GraphQLContext
import bosca.graphql.server.GraphQLRequest
import bosca.graphql.server.runtimeWiring
import bosca.pubsub.PubSubService
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the workspace YAML, SDL, generated dispatchers, input validation, and job creation together. */
class GitManualPipelineGraphQLIntegrationTest {

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `manual image inputs travel through GraphQL into the queued build`() = runTest {
        ProviderRegistry.clear()
        provides<Json> { Json }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("manual-pipeline-test") }
        provides<PubSubService> { mockk(relaxed = true) }
        val pool = mockk<ConnectionPool>()
        every { pool.connection() } answers { mockk<ConnectionManager>(relaxed = true) }
        provides<ConnectionPool> { pool }

        val repositoryId = UUID.random()
        val pipelineId = UUID.random()
        val principalId = UUID.random()
        val pipeline = Pipeline(id = pipelineId, repositoryId = repositoryId, filePath = ".bosca/pipelines/release-image.yaml", name = "Image", configHash = "hash")
        val definition = PipelineYamlParser().parse(File("../../.bosca/pipelines/release-image.yaml").readText(), pipeline.filePath)
        val pipelines = mockk<PipelineService>()
        val repositories = mockk<RepositoryService>()
        val browse = mockk<RepositoryBrowseService>()
        val jobs = mockk<PipelineJobService>(relaxed = true)
        val runRepository = mockk<PipelineRunRepository>(relaxed = true)
        coEvery { pipelines.findById(pipelineId) } returns pipeline
        coEvery { pipelines.parseDefinition(repositoryId, any(), pipeline.filePath) } returns definition
        coEvery { repositories.findById(repositoryId) } returns mockk<Repository>()
        coEvery { browse.resolveRef(repositoryId, "refs/heads/main") } returns "commit-sha"
        coEvery { runRepository.create(any()) } answers { firstArg<PipelineRun>().copy(id = UUID.random()) }
        val runs = PipelineRunServiceImpl(runRepository, jobs, mockk(relaxed = true), pipelines, Json,
            mockk(relaxed = true), mockk<bosca.kubernetes.service.KubernetesJobDispatchService>(relaxed = true).asProvider(),
            mockk(relaxed = true), mockk(relaxed = true))
        val permissionEvaluator = mockk<bosca.git.security.RepositoryPermissionEvaluator>(relaxed = true)
        val authentication = mockk<AuthenticationContext>()
        every { authentication.principal() } returns mockk { every { id } returns principalId }
        val query = GitPipelineQuery(pipelines, runs, jobs, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), repositories, permissionEvaluator, Json)
        val mutation = GitPipelineMutation(pipelines, runs, jobs, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), repositories, browse, mockk(relaxed = true),
            permissionEvaluator, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val queryField = GitPipelineQueryDispatcher(query).type.fieldResolvers.getValue("pipelineInputs")
        val mutationField = GitPipelineMutationDispatcher(mutation).type.fieldResolvers.getValue("triggerPipeline")
        val runIdField = GitPipelineRunControllerDispatcher(GitPipelineRunController(jobs, mockk(relaxed = true)))
            .type.fieldResolvers.getValue("id")
        val sdl = File("../git/src/main/resources/graphql/git.graphqls").readLines()
        val inputFieldSdl = sdl.first { it.trimStart().startsWith("pipelineInputs(") }
        val triggerFieldSdl = sdl.first { it.trimStart().startsWith("triggerPipeline(") }
        val executable = ExecutableSchema.fromSdl(
            """
                scalar UUID
                scalar JSON
                type Query { git: Git! }
                type Mutation { git: GitMutation! }
                type Git { $inputFieldSdl }
                type GitMutation { $triggerFieldSdl }
                type GitPipelineRun { id: UUID! }
            """.trimIndent(),
            runtimeWiring {
                scalar("UUID", ExtendedScalars.Uuid)
                scalar("JSON", ExtendedScalars.Json)
                type("Query") { field("git") { bosca.git.graphql.Git } }
                type("Mutation") { field("git") { GitMutation } }
                type("Git") { field("pipelineInputs", queryField) }
                type("GitMutation") { field("triggerPipeline", mutationField) }
                type("GitPipelineRun") { field("id", runIdField) }
            },
        )
        val graphql = GraphQL(executable)
        val context = GraphQLContext(mapOf("authenticationContext" to authentication))
        val variables = mapOf("pipelineId" to pipelineId.toString(), "ref" to "refs/heads/main")
        val declarations = graphql.execute(GraphQLRequest(
            query = "query(\$pipelineId: UUID!, \$ref: String!) { git { pipelineInputs(pipelineId: \$pipelineId, ref: \$ref) } }",
            variables = variables, context = context,
        ))
        assertTrue(declarations.errors.isEmpty(), declarations.errors.toString())
        assertEquals(setOf("image", "version"), declarations.data?.jsonObject?.getValue("git")?.jsonObject?.getValue("pipelineInputs")?.jsonObject?.keys)

        val request = "mutation(\$pipelineId: UUID!, \$ref: String!, \$inputs: JSON) { git { triggerPipeline(pipelineId: \$pipelineId, ref: \$ref, inputs: \$inputs) { id } } }"
        for (inputs in listOf(mapOf("version" to "7.4.0"), mapOf("image" to "unknown", "version" to "7.4.0"))) {
            val rejected = graphql.execute(GraphQLRequest(request, variables = variables + ("inputs" to inputs), context = context))
            assertTrue(rejected.errors.isNotEmpty())
        }
        coVerify(exactly = 0) { runRepository.create(any()) }

        val result = graphql.execute(GraphQLRequest(request,
            variables = variables + ("inputs" to mapOf("image" to "bosca-server", "version" to "7.4.0")), context = context))
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        coVerify {
            runRepository.create(match { it.commitSha == "commit-sha" && it.triggeredBy == principalId })
            jobs.createJobs(any(), match { queued ->
                val step = queued.getValue("publish-image").steps.first { it.name == "Build and push image" }
                val expressions = PipelineExpressionParser()
                val execution = ExpressionContext(extra = step.env)
                expressions.interpolate(step.env.getValue("IMAGE"), execution) == "bosca-server" &&
                    expressions.interpolate(step.env.getValue("VERSION"), execution) == "7.4.0"
            })
        }
    }
}
