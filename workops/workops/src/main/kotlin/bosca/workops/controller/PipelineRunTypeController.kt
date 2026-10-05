package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.workops.model.pipeline.PipelineRun
import bosca.workops.service.PipelineRunService

// ── Marker objects ────────────────────────────────────────────────────

@TypeController(type = "WorkOpsPipelineRun")
class PipelineRunTypeController(
    private val stageService: PipelineRunService,
) : GraphQLController<PipelineRun> {
    @Field fun id(r: PipelineRun) = r.id
    @Field fun projectId(r: PipelineRun) = r.projectId
    @Field fun versionId(r: PipelineRun) = r.versionId
    @Field fun pipelineId(r: PipelineRun) = r.pipelineId
    @Field fun pipelineName(r: PipelineRun) = r.pipelineName
    @Field fun triggerType(r: PipelineRun) = r.triggerType
    @Field fun triggerRef(r: PipelineRun) = r.triggerRef
    @Field fun status(r: PipelineRun) = r.status
    @Field fun startedAt(r: PipelineRun) = r.startedAt
    @Field fun completedAt(r: PipelineRun) = r.completedAt
    @Field fun externalUrl(r: PipelineRun) = r.externalUrl
    @Field suspend fun stages(r: PipelineRun) = stageService.listStages(r.id)
    @Field fun version(r: PipelineRun) = r.version
}
