package bosca.git.graphql

import bosca.git.model.PullRequestEvent
import bosca.git.model.PullRequestEventAction
import bosca.git.model.ReviewStatus
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID

/** Explicit schema-first fields for pull-request activity subscription events. */
@TypeController(type = "GitPullRequestActivityEvent")
class GitPullRequestActivityEventController : GraphQLController<PullRequestEvent> {
    @Field fun repositoryId(source: PullRequestEvent): UUID = source.repositoryId
    @Field fun pullRequestId(source: PullRequestEvent): UUID = source.pullRequestId
    @Field fun number(source: PullRequestEvent): Int = source.number
    @Field fun action(source: PullRequestEvent): PullRequestEventAction = source.action
    @Field fun title(source: PullRequestEvent): String = source.title
    @Field fun sourceBranch(source: PullRequestEvent): String = source.sourceBranch
    @Field fun targetBranch(source: PullRequestEvent): String = source.targetBranch
    @Field fun authorId(source: PullRequestEvent): UUID = source.authorId
    @Field fun taskKeys(source: PullRequestEvent): List<String> = source.taskKeys.toList()
    @Field fun repositoryName(source: PullRequestEvent): String = source.repositoryName
    @Field fun recipientIds(source: PullRequestEvent): List<UUID> = source.recipientIds.toList()
    @Field fun actorId(source: PullRequestEvent): UUID? = source.actorId
    @Field fun actorName(source: PullRequestEvent): String? = source.actorName
    @Field fun body(source: PullRequestEvent): String? = source.body
    @Field fun filePath(source: PullRequestEvent): String? = source.filePath
    @Field fun lineNumber(source: PullRequestEvent): Int? = source.lineNumber
    @Field fun reviewStatus(source: PullRequestEvent): ReviewStatus? = source.reviewStatus
    @Field fun assigneeId(source: PullRequestEvent): UUID? = source.assigneeId
}
