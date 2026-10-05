@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.pipelines.git.PipelineRepoValidationError
import bosca.pipelines.git.PipelineSyncResult
import bosca.pipelines.model.PipelineRunStatus
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/** Per-node trace of a dry run; the GraphQL type is `PipelineDryRun`. */
data class PipelineDryRunResult(
    val outputs: JsonElement,
    val actions: JsonElement,
    val errors: JsonElement,
    val skipped: JsonElement,
    val error: String?,
)

@TypeController(type = "PipelineDryRun")
class PipelineDryRunController : GraphQLController<PipelineDryRunResult> {

    @Field
    fun outputs(source: PipelineDryRunResult): JsonElement = source.outputs

    @Field
    fun actions(source: PipelineDryRunResult): JsonElement = source.actions

    @Field
    fun errors(source: PipelineDryRunResult): JsonElement = source.errors

    @Field
    fun skipped(source: PipelineDryRunResult): JsonElement = source.skipped

    @Field
    fun error(source: PipelineDryRunResult): String? = source.error
}

/** Result of an on-demand (manual / API) pipeline run; the GraphQL type is `PipelineRunResult`. */
data class PipelineRunResultModel(
    val ok: Boolean,
    val runId: UUID,
    val status: PipelineRunStatus,
    val output: JsonElement?,
    val error: String?,
)

@TypeController(type = "PipelineRunResult")
class PipelineRunResultController : GraphQLController<PipelineRunResultModel> {

    @Field
    fun ok(source: PipelineRunResultModel): Boolean = source.ok

    /** The durable run's id — use it to track a run that suspended (status SUSPENDED). */
    @Field
    fun runId(source: PipelineRunResultModel): UUID = source.runId

    /** OK (finished), FAILED/CANCELLED (terminal failure), or SUSPENDED (parked past the brief block). */
    @Field
    fun status(source: PipelineRunResultModel): PipelineRunStatus = source.status

    @Field
    fun output(source: PipelineRunResultModel): JsonElement? = source.output

    @Field
    fun error(source: PipelineRunResultModel): String? = source.error
}

/** Result of a Git sync operation; the GraphQL type is `PipelineRepoSyncResult`. */
data class PipelineRepoSyncResultModel(
    val ok: Boolean,
    val commitSha: String?,
    val validationErrors: List<PipelineRepoValidationError>?,
    val errorMessage: String?,
)

internal fun PipelineSyncResult.toModel(): PipelineRepoSyncResultModel = when (this) {
    is PipelineSyncResult.Ok -> PipelineRepoSyncResultModel(true, commitSha, null, null)
    is PipelineSyncResult.ValidationFailed -> PipelineRepoSyncResultModel(false, null, errors, null)
    is PipelineSyncResult.Failure -> PipelineRepoSyncResultModel(false, null, null, message)
}

@TypeController(type = "PipelineRepoSyncResult")
class PipelineRepoSyncResultController : GraphQLController<PipelineRepoSyncResultModel> {

    @Field
    fun ok(source: PipelineRepoSyncResultModel): Boolean = source.ok

    @Field
    fun commitSha(source: PipelineRepoSyncResultModel): String? = source.commitSha

    @Field
    fun validationErrors(source: PipelineRepoSyncResultModel): List<PipelineRepoValidationError>? =
        source.validationErrors

    @Field
    fun errorMessage(source: PipelineRepoSyncResultModel): String? = source.errorMessage
}

@TypeController(type = "PipelineRepoValidationError")
class PipelineRepoValidationErrorController : GraphQLController<PipelineRepoValidationError> {

    @Field
    fun path(source: PipelineRepoValidationError): String = source.path

    @Field
    fun message(source: PipelineRepoValidationError): String = source.message
}
