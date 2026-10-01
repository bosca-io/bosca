package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchTask
import bosca.meilisearch.admin.model.MeilisearchTaskError

/**
 * Resolves fields on the MeilisearchTask GraphQL type.
 */
@TypeController
class MeilisearchTaskController : GraphQLController<MeilisearchTask> {

    @Field fun uid(task: MeilisearchTask) = task.uid
    @Field fun type(task: MeilisearchTask) = task.type
    @Field fun status(task: MeilisearchTask) = task.status
    @Field fun indexUid(task: MeilisearchTask) = task.indexUid
    @Field fun enqueuedAt(task: MeilisearchTask) = task.enqueuedAt
    @Field fun startedAt(task: MeilisearchTask) = task.startedAt
    @Field fun finishedAt(task: MeilisearchTask) = task.finishedAt
    @Field fun duration(task: MeilisearchTask) = task.duration
    @Field fun error(task: MeilisearchTask): MeilisearchTaskError? = task.error
    @Field fun canceledBy(task: MeilisearchTask) = task.canceledBy
}
