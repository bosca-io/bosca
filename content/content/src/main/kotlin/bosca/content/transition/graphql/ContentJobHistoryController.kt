package bosca.content.transition.graphql

import bosca.content.transition.model.JobHistory
import bosca.di.MissingProviderException
import bosca.di.provide
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer

/**
 * Wrapper that adapts [JobHistory] instances for GraphQL resolution as the
 * `ContentJobHistory` type. Used by both metadata and collection workflow
 * controllers to expose active job information.
 */
class ContentJobHistory(val job: JobHistory)

@TypeController
class ContentJobHistoryController : GraphQLController<ContentJobHistory> {

    @Field
    fun jobName(history: ContentJobHistory) = history.job.jobName

    @Field
    suspend fun displayName(history: ContentJobHistory): String {
        val name = history.job.jobName
        try {
            val enqueuer = provide<JobConfigurationEnqueuer>(name)
            if (enqueuer.displayName.isNotBlank() && enqueuer.displayName != name) {
                return enqueuer.displayName
            }
        } catch (_: MissingProviderException) {
        }
        return name
            .split('-')
            .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    @Field
    fun jobId(history: ContentJobHistory) = history.job.jobId

    @Field
    fun status(history: ContentJobHistory) = history.job.status

    @Field
    fun created(history: ContentJobHistory) = history.job.created

    @Field
    fun complete(history: ContentJobHistory) = history.job.complete

    @Field
    fun success(history: ContentJobHistory) = history.job.success

    @Field
    fun delayedUntil(history: ContentJobHistory) = history.job.delayedUntil
}
