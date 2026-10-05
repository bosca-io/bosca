package bosca.analytics.graphql

import bosca.analytics.model.ErrorGroup
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Field resolver for the GraphQL `ErrorGroup` type. The data class
 * lives in `core-analytics`; this controller exposes its properties as
 * GraphQL fields and is intentionally side-effect free.
 */
@TypeController
class ErrorGroupController : GraphQLController<ErrorGroup> {

    @Field
    fun fingerprint(group: ErrorGroup) = group.fingerprint

    @Field
    fun appId(group: ErrorGroup) = group.appId

    @Field
    fun type(group: ErrorGroup) = group.type

    @Field
    fun message(group: ErrorGroup) = group.message

    @Field
    fun fatal(group: ErrorGroup) = group.fatal

    @Field
    fun status(group: ErrorGroup) = group.status

    @Field
    fun assigneeId(group: ErrorGroup) = group.assigneeId

    @Field
    fun firstSeen(group: ErrorGroup) = group.firstSeen

    @Field
    fun lastSeen(group: ErrorGroup) = group.lastSeen

    @Field
    fun eventCount(group: ErrorGroup) = group.eventCount

    @Field
    fun sampleEventId(group: ErrorGroup) = group.sampleEventId

    @Field
    fun sampleStack(group: ErrorGroup) = group.sampleStack

    @Field
    fun aiSummary(group: ErrorGroup) = group.aiSummary

    @Field
    fun aiSummaryAt(group: ErrorGroup) = group.aiSummaryAt

    @Field
    fun created(group: ErrorGroup) = group.created

    @Field
    fun modified(group: ErrorGroup) = group.modified
}
