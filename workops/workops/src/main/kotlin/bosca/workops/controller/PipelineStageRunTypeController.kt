package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.workops.model.pipeline.PipelineStageRun

// ── Marker objects ────────────────────────────────────────────────────

@TypeController(type = "WorkOpsPipelineStageRun")
class PipelineStageRunTypeController : GraphQLController<PipelineStageRun> {
    @Field fun id(s: PipelineStageRun) = s.id
    @Field fun pipelineRunId(s: PipelineStageRun) = s.pipelineRunId
    @Field fun stageName(s: PipelineStageRun) = s.stageName
    @Field fun status(s: PipelineStageRun) = s.status
    @Field fun startedAt(s: PipelineStageRun) = s.startedAt
    @Field fun completedAt(s: PipelineStageRun) = s.completedAt
    @Field fun externalUrl(s: PipelineStageRun) = s.externalUrl
}
