package bosca.backup.graphql

import bosca.backup.ObjectBackupRunSummary
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Exposes logical object counts and byte totals for live or recorded outcomes. */
@TypeController("ObjectBackupRunSummary")
class ObjectBackupRunSummaryController : GraphQLController<ObjectBackupRunSummary> {
    @Field
    fun total(summary: ObjectBackupRunSummary) = summary.total

    @Field
    fun pending(summary: ObjectBackupRunSummary) = summary.pending

    @Field
    fun running(summary: ObjectBackupRunSummary) = summary.running

    @Field
    fun completed(summary: ObjectBackupRunSummary) = summary.completed

    @Field
    fun failed(summary: ObjectBackupRunSummary) = summary.failed

    @Field
    fun totalBytes(summary: ObjectBackupRunSummary) = summary.totalBytes

    @Field
    fun verifiedBytes(summary: ObjectBackupRunSummary) = summary.verifiedBytes
}
