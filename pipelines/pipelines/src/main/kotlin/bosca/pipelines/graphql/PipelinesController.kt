@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.graphql

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.pipelines.model.BrokenPipeline
import bosca.pipelines.model.NodeMetrics
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.model.PipelineRunLogWithName
import bosca.pipelines.model.PipelineNamedShape
import bosca.pipelines.model.PipelineSecret
import bosca.pipelines.node.NodeDescriptor
import bosca.pipelines.node.PipelineNodeSerializers
import bosca.pipelines.node.ShapeField
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineSecretService
import bosca.pipelines.service.PipelineShapeService
import bosca.pipelines.service.PipelineService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.SerializerCache
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/** GraphQL namespace for pipeline queries (`Query.pipelines`). */
object Pipelines

/** Field wiring for `Query.pipelines` — admin-gated reads over pipelines, run history, and live runs. */
@TypeController
class PipelinesController(
    private val service: PipelineService,
    private val groups: GroupEvaluator,
    private val runService: PipelineRunService,
    private val secretService: PipelineSecretService,
    private val shapeService: PipelineShapeService,
) : GraphQLController<Pipelines> {

    @Field
    suspend fun runs(
        authentication: AuthenticationContext,
        pipelineId: UUID,
        offset: Int?,
        limit: Int?,
    ): List<PipelineRunLogWithName> {
        groups.verifyHasAdminGroup(authentication)
        return runService.listRunHistory(
            pipelineId,
            (offset ?: 0).toLong(),
            (limit ?: 50).coerceIn(1, 200),
        )
    }

    @Field
    suspend fun allRuns(
        authentication: AuthenticationContext,
        offset: Int?,
        limit: Int?,
    ): List<PipelineRunLogWithName> {
        groups.verifyHasAdminGroup(authentication)
        return runService.listAllRunHistory(
            (offset ?: 0).toLong(),
            (limit ?: 50).coerceIn(1, 200),
        )
    }

    @Field
    suspend fun all(authentication: AuthenticationContext): List<Pipeline> {
        groups.verifyHasAdminGroup(authentication)
        return service.getAll()
    }

    @Field
    suspend fun pipeline(authentication: AuthenticationContext, id: UUID): Pipeline? {
        groups.verifyHasAdminGroup(authentication)
        return service.get(id)
    }

    @Field
    suspend fun broken(authentication: AuthenticationContext): List<BrokenPipeline> {
        groups.verifyHasAdminGroup(authentication)
        return service.getBroken()
    }

    @OptIn(InternalDI::class)
    @Field
    suspend fun nodeTypes(authentication: AuthenticationContext): List<NodeDescriptor> {
        groups.verifyHasAdminGroup(authentication)
        return ProviderRegistry.findAll(PipelineNodeSerializers::class)
            .filter { it.exists }
            .flatMap { it.get().descriptors }
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.category }, { it.label }))
    }

    /** All registered concrete pipeline value types and their domain interfaces/base types. */
    @Field
    suspend fun types(authentication: AuthenticationContext): List<String> {
        groups.verifyHasAdminGroup(authentication)
        return SerializerCache.cataloguedTypes()
    }

    @Field
    suspend fun activeRuns(
        authentication: AuthenticationContext,
        offset: Int?,
        limit: Int?,
    ): List<PipelineRun> {
        groups.verifyHasAdminGroup(authentication)
        return runService.listActive((offset ?: 0).toLong(), (limit ?: 50).coerceIn(1, 200))
    }

    /** The all-PENDING step plan of the triggered pipeline(s) accepting [eventName] — the pre-run view. */
    @Field
    suspend fun stepsForTrigger(authentication: AuthenticationContext, eventName: String): List<bosca.pipelines.model.RunStep> =
        runService.stepsForTrigger(eventName)

    /**
     * The dead-letter queue — runs that terminally FAILED, newest first,
     * for operator triage. Restart one with `Mutation.pipelines.restartRun`. Admin-gated.
     */
    @Field
    suspend fun deadLetter(
        authentication: AuthenticationContext,
        offset: Int?,
        limit: Int?,
    ): List<PipelineRun> {
        groups.verifyHasAdminGroup(authentication)
        return runService.listDeadLetter((offset ?: 0).toLong(), (limit ?: 50).coerceIn(1, 200))
    }

    /**
     * A single durable run's live state by id — select its `nodes` field for the
     * per-node execution timeline. Works for both in-flight and finished runs (the run row persists
     * with its terminal status until retention). Null when the run is absent or soft-deleted.
     */
    @Field
    suspend fun run(authentication: AuthenticationContext, runId: UUID): PipelineRun? {
        groups.verifyHasAdminGroup(authentication)
        return runService.get(runId)
    }

    /**
     * Per-node execution metrics for a pipeline across its run history —
     * count, failures, and p50/p95 completion duration per node, busiest first. Admin-gated.
     */
    @Field
    suspend fun nodeMetrics(authentication: AuthenticationContext, pipelineId: UUID): List<NodeMetrics> {
        groups.verifyHasAdminGroup(authentication)
        return runService.nodeMetrics(pipelineId)
    }

    /** Node secrets' metadata (names + timestamps; never values). Admin-gated. */
    @Field
    suspend fun secrets(authentication: AuthenticationContext): List<PipelineSecret> {
        groups.verifyHasAdminGroup(authentication)
        return secretService.listSecrets()
    }

    /** All reusable named object shapes — offered as first-class types in the editor's Input/Output pickers. */
    @Field
    suspend fun shapes(): List<PipelineNamedShape> = shapeService.list()
}

/** Field wiring for the `PipelineNamedShape` GraphQL type (a reusable named object shape); source is [PipelineNamedShape]. */
@TypeController(type = "PipelineNamedShape")
class PipelineNamedShapeController : GraphQLController<PipelineNamedShape> {

    @Field
    fun name(source: PipelineNamedShape): String = source.name

    @Field
    fun fields(source: PipelineNamedShape): List<ShapeField> = source.fields
}
