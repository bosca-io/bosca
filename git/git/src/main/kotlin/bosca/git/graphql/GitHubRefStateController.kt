package bosca.git.graphql

import bosca.git.model.GitHubRefState
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@TypeController(type = "GitHubRefState")
class GitHubRefStateController : GraphQLController<GitHubRefState> {
    @Field fun repositoryId(source: GitHubRefState): UUID = source.repositoryId
    @Field fun ref(source: GitHubRefState): String = source.ref
    @Field fun sha(source: GitHubRefState): String? = source.sha
    @Field fun synchronized(source: GitHubRefState): Boolean = source.synchronized
    @Field fun boscaSha(source: GitHubRefState): String? = source.boscaSha
    @Field fun githubSha(source: GitHubRefState): String? = source.githubSha
    @Field fun conflict(source: GitHubRefState): Boolean = source.conflict
    @Field fun modified(source: GitHubRefState): OffsetDateTime = source.modified
}
