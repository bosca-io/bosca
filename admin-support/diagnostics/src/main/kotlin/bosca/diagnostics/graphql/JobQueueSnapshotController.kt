package bosca.diagnostics.graphql

import bosca.diagnostics.model.JobQueueSnapshot
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class JobQueueSnapshotController : GraphQLController<JobQueueSnapshot> {

    @Field
    fun queue(snapshot: JobQueueSnapshot) = snapshot.queue

    @Field
    fun jobs(snapshot: JobQueueSnapshot) = snapshot.jobs
}
