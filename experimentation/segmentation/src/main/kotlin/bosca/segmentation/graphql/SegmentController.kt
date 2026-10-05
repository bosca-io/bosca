package bosca.segmentation.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.scheduler.service.SchedulerService
import bosca.segmentation.model.Segment
import bosca.segmentation.model.SegmentMember
import bosca.segmentation.model.SegmentStatus
import bosca.segmentation.model.SegmentType
import bosca.segmentation.service.SegmentService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Resolves fields on the Segment GraphQL type, including nested member loading
 * and derived fields like the evaluation schedule from the linked scheduler job.
 */
@TypeController(type = "Segment")
class SegmentController(
    private val segmentService: SegmentService,
    private val schedulerService: SchedulerService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Segment> {

    @Field
    fun id(segment: Segment): UUID = segment.id

    @Field
    fun name(segment: Segment): String = segment.name

    @Field
    fun description(segment: Segment): String = segment.description

    @Field
    fun type(segment: Segment): SegmentType = segment.type

    @Field
    fun status(segment: Segment): SegmentStatus = segment.status

    @Field
    fun analyticsQueryId(segment: Segment): UUID? = segment.analyticsQueryId

    @Field
    fun configuration(segment: Segment): JsonElement? = segment.configuration

    @Field
    fun scheduledJobId(segment: Segment): UUID? = segment.scheduledJobId

    /**
     * Resolves the cron expression for this segment's evaluation schedule
     * by looking up the linked scheduled job.
     */
    @Field
    suspend fun evaluationSchedule(segment: Segment): String? {
        val jobId = segment.scheduledJobId ?: return null
        return schedulerService.getJob(jobId)?.cronExpression
    }

    @Field
    fun lastEvaluated(segment: Segment): OffsetDateTime? = segment.lastEvaluated

    @Field
    fun memberCount(segment: Segment): Long = segment.memberCount

    @Field
    fun created(segment: Segment): OffsetDateTime = segment.created

    @Field
    fun modified(segment: Segment): OffsetDateTime = segment.modified

    @Field
    suspend fun members(
        authentication: AuthenticationContext,
        segment: Segment,
        offset: Long,
        limit: Int
    ): List<SegmentMember> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return segmentService.getMembers(segment.id, maxOf(offset, 0), limit.coerceIn(1, 100))
    }
}
