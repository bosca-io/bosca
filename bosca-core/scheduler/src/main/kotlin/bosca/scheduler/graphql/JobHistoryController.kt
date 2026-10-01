package bosca.scheduler.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.scheduler.model.JobHistory
import kotlinx.serialization.json.JsonElement

@TypeController
class JobHistoryController : GraphQLController<JobHistory> {

    @Field
    fun id(entry: JobHistory) = entry.id

    @Field
    fun scheduledJobId(entry: JobHistory) = entry.scheduledJobId

    @Field
    fun jobId(entry: JobHistory) = entry.jobId

    @Field
    fun name(entry: JobHistory) = entry.name

    @Field
    fun scheduledFor(entry: JobHistory) = entry.scheduledFor

    @Field
    fun triggeredAt(entry: JobHistory) = entry.triggeredAt

    @Field
    fun source(entry: JobHistory) = entry.source

    @Field
    fun status(entry: JobHistory) = entry.status

    @Field
    fun completedAt(entry: JobHistory) = entry.completedAt

    @Field
    fun errorMessage(entry: JobHistory) = entry.errorMessage

    @Field
    fun wasCatchUp(entry: JobHistory) = entry.wasCatchUp

    @Field
    fun delayedUntil(entry: JobHistory) = entry.delayedUntil

    @Field
    fun parentJobId(entry: JobHistory) = entry.parentJobId

    @Field
    fun definition(entry: JobHistory) = entry.definition

    @Field
    fun context(entry: JobHistory): JsonElement? = entry.context
}
