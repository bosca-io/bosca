package bosca.git.ci.graphql

import bosca.git.model.PipelineEvent
import bosca.git.model.PipelineRunStatus
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID

/** Explicit schema-first fields for repository-scoped pipeline subscription events. */
@TypeController(type = "GitPipelineStatusEvent")
class GitPipelineEventController : GraphQLController<PipelineEvent> {
    @Field fun repositoryId(source: PipelineEvent): UUID = source.repositoryId
    @Field fun pipelineRunId(source: PipelineEvent): UUID = source.pipelineRunId
    @Field fun pipelineId(source: PipelineEvent): UUID = source.pipelineId
    @Field fun status(source: PipelineEvent): PipelineRunStatus = source.status
}
