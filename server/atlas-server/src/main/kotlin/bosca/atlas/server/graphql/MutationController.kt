package bosca.atlas.server.graphql

import bosca.git.graphql.GitHubMutation
import bosca.git.graphql.GitMutation
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.pipelines.graphql.PipelinesMutation
import bosca.profile.profile.graphql.ProfilesMutation
import bosca.scheduler.graphql.SchedulerMutation
import bosca.security.graphql.SecurityMutation

@TypeController
class MutationController : GraphQLController<Mutation> {
    @Field fun artifactsAdmin() = bosca.artifacts.admin.graphql.ArtifactsAdminMutation

    @Field fun git() = GitMutation

    @Field fun github() = GitHubMutation

    @Field fun security() = SecurityMutation

    @Field fun profiles() = ProfilesMutation

    @Field fun pipelines() = PipelinesMutation

    @Field fun scheduler() = SchedulerMutation
}
