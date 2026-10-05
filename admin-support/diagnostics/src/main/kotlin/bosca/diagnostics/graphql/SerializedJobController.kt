package bosca.diagnostics.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.sharedqueue.jobs.SerializedJob

@TypeController
class SerializedJobController : GraphQLController<SerializedJob> {

    @Field
    fun id(job: SerializedJob) = job.id

    @Field
    fun status(job: SerializedJob) = job.status

    @Field
    fun failures(job: SerializedJob) = job.failures

    @Field
    fun maxFailures(job: SerializedJob) = job.maxFailures

    @Field
    fun created(job: SerializedJob) = job.created

    @Field
    fun modified(job: SerializedJob) = job.modified

    @Field
    fun executor(job: SerializedJob) = job.executor

    @Field
    fun executorName(job: SerializedJob) = job.executorName

    @Field
    fun parentId(job: SerializedJob) = job.parentId

    @Field
    fun children(job: SerializedJob) = job.children
}
