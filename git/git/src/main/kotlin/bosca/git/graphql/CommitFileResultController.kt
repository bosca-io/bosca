package bosca.git.graphql

import bosca.git.service.CommitFileResult
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController(type = "CommitFileResult")
class CommitFileResultController : GraphQLController<CommitFileResult> {

    @Field fun commitSha(commitFileResult: CommitFileResult): String = commitFileResult.commitSha
    @Field fun branch(commitFileResult: CommitFileResult): String = commitFileResult.branch
    @Field fun path(commitFileResult: CommitFileResult): String = commitFileResult.path
}
