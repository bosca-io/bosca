package bosca.segmentation.service

import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.analytics.model.QueryParameterType
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.analytics.service.AnalyticsQueryService
import bosca.db.connection
import bosca.db.transaction
import bosca.scheduler.model.ScheduledJobInput
import bosca.scheduler.service.SchedulerService
import bosca.segmentation.events.SegmentCreated
import bosca.segmentation.events.SegmentDeleted
import bosca.segmentation.events.SegmentEvaluated
import bosca.segmentation.events.SegmentMembersAdded
import bosca.segmentation.events.SegmentMembersRemoved
import bosca.segmentation.events.SegmentUpdated
import bosca.segmentation.events.dispatch
import bosca.segmentation.jobs.EvaluateSegmentJob
import bosca.segmentation.model.Segment
import bosca.segmentation.model.SegmentInput
import bosca.segmentation.model.SegmentMember
import bosca.segmentation.model.SegmentType
import bosca.segmentation.repository.SegmentMemberRepository
import bosca.segmentation.repository.SegmentRepository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlin.uuid.toJavaUuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@ServiceImplementation
class SegmentServiceImpl(
    private val segmentRepository: SegmentRepository,
    private val memberRepository: SegmentMemberRepository,
    private val analyticsQueryExecutionService: AnalyticsQueryExecutionService,
    private val analyticsQueryService: AnalyticsQueryService,
    private val schedulerService: SchedulerService
) : SegmentService {

    override suspend fun getAll(offset: Long, limit: Int): List<Segment> {
        return segmentRepository.getAll(offset, limit)
    }

    override suspend fun getById(id: UUID): Segment? {
        return segmentRepository.getById(id)
    }

    override suspend fun getByIds(ids: List<UUID>): List<Segment> {
        if (ids.isEmpty()) return emptyList()
        return segmentRepository.getByIds(ids)
    }

    override suspend fun add(input: SegmentInput): Segment = transaction {
        val segment = Segment(
            name = input.name,
            description = input.description,
            type = input.type,
            status = input.status,
            analyticsQueryId = input.analyticsQueryId,
            configuration = input.configuration
        )
        val created = segmentRepository.add(segment)
        val schedule = if (input.type == SegmentType.DYNAMIC) input.evaluationSchedule else null
        val result = if (schedule != null) {
            val jobId = createScheduledJob(created.id, created.name, schedule)
            segmentRepository.updateScheduledJobId(created.id, jobId)
        } else {
            created
        }
        SegmentCreated(result.id, result.name).dispatch()
        result
    }

    override suspend fun edit(id: UUID, input: SegmentInput): Segment = transaction {
        val existing = segmentRepository.getById(id) ?: error("Segment not found: $id")
        val effectiveSchedule = if (input.type == SegmentType.DYNAMIC) input.evaluationSchedule else null
        val updated = existing.copy(
            name = input.name,
            description = input.description,
            type = input.type,
            status = input.status,
            analyticsQueryId = input.analyticsQueryId,
            configuration = input.configuration
        )
        val saved = segmentRepository.update(updated)
        val jobId = syncScheduledJob(
            segmentId = id,
            segmentName = input.name,
            existingJobId = existing.scheduledJobId,
            evaluationSchedule = effectiveSchedule
        )
        val result = if (jobId != existing.scheduledJobId) {
            segmentRepository.updateScheduledJobId(id, jobId)
        } else {
            saved
        }
        SegmentUpdated(result.id, result.name).dispatch()
        result
    }

    override suspend fun delete(id: UUID) = transaction {
        val existing = segmentRepository.getById(id)
        val jobId = existing?.scheduledJobId
        if (jobId != null) {
            schedulerService.deleteJob(jobId)
        }
        memberRepository.removeAllMembers(id)
        segmentRepository.deleteById(id)
        SegmentDeleted(id).dispatch()
    }

    override suspend fun getMembers(segmentId: UUID, offset: Long, limit: Int): List<SegmentMember> {
        return memberRepository.getMembers(segmentId, offset, limit)
    }

    override suspend fun getMemberCount(segmentId: UUID): Long {
        return memberRepository.getMemberCount(segmentId)
    }

    override suspend fun addMembers(segmentId: UUID, profileIds: List<UUID>) = transaction<Unit> {
        val segment = segmentRepository.getById(segmentId) ?: error("Segment not found: $segmentId")
        require(segment.type == SegmentType.STATIC) { "Members can only be added to STATIC segments" }
        addMembersBatch(segmentId, profileIds)
        val memberCount = memberRepository.getMemberCount(segmentId)
        segmentRepository.updateEvaluation(id = segmentId, lastEvaluated = OffsetDateTime.now(), memberCount = memberCount)
        if (profileIds.isNotEmpty()) {
            SegmentMembersAdded(segmentId, profileIds).dispatch()
        }
    }

    override suspend fun removeMembers(segmentId: UUID, profileIds: List<UUID>) = transaction<Unit> {
        segmentRepository.getById(segmentId) ?: error("Segment not found: $segmentId")
        removeMembersBatch(segmentId, profileIds)
        val memberCount = memberRepository.getMemberCount(segmentId)
        segmentRepository.updateEvaluation(id = segmentId, lastEvaluated = OffsetDateTime.now(), memberCount = memberCount)
        if (profileIds.isNotEmpty()) {
            SegmentMembersRemoved(segmentId, profileIds).dispatch()
        }
    }

    override suspend fun evaluate(segmentId: UUID): Segment = transaction {
        val segment = segmentRepository.getById(segmentId) ?: error("Segment not found: $segmentId")
        if (segment.type != SegmentType.DYNAMIC) {
            error("Only dynamic segments can be evaluated")
        }
        val queryId = segment.analyticsQueryId ?: error("Dynamic segment has no analytics query: $segmentId")
        memberRepository.removeAllMembers(segmentId)
        var totalCount = 0L
        executeQueryPaged(queryId) { profileIds ->
            addMembersBatch(segmentId, profileIds)
            totalCount += profileIds.size
        }
        val result = segmentRepository.updateEvaluation(
            id = segmentId,
            lastEvaluated = OffsetDateTime.now(),
            memberCount = totalCount
        )
        SegmentEvaluated(segmentId, totalCount).dispatch()
        result
    }

    override suspend fun populateFromQuery(segmentId: UUID, analyticsQueryId: UUID): Segment = transaction {
        val segment = segmentRepository.getById(segmentId) ?: error("Segment not found: $segmentId")
        require(segment.type == SegmentType.STATIC) { "Only static segments can be populated from a query" }
        executeQueryPaged(analyticsQueryId) { profileIds ->
            addMembersBatch(segmentId, profileIds)
        }
        val memberCount = memberRepository.getMemberCount(segmentId)
        val result = segmentRepository.updateEvaluation(
            id = segmentId,
            lastEvaluated = OffsetDateTime.now(),
            memberCount = memberCount
        )
        SegmentEvaluated(segmentId, memberCount).dispatch()
        result
    }

    override suspend fun removeFromQuery(segmentId: UUID, analyticsQueryId: UUID): Segment = transaction {
        val segment = segmentRepository.getById(segmentId) ?: error("Segment not found: $segmentId")
        require(segment.type == SegmentType.STATIC) { "Only static segments can have members removed from a query" }
        executeQueryPaged(analyticsQueryId) { profileIds ->
            removeMembersBatch(segmentId, profileIds)
        }
        val memberCount = memberRepository.getMemberCount(segmentId)
        val result = segmentRepository.updateEvaluation(
            id = segmentId,
            lastEvaluated = OffsetDateTime.now(),
            memberCount = memberCount
        )
        SegmentEvaluated(segmentId, memberCount).dispatch()
        result
    }

    override suspend fun removeFromSegment(segmentId: UUID, removeSegmentId: UUID): Segment = transaction {
        val segment = segmentRepository.getById(segmentId) ?: error("Segment not found: $segmentId")
        require(segment.type == SegmentType.STATIC) { "Only static segments can have members removed by another segment" }
        segmentRepository.getById(removeSegmentId) ?: error("Source segment not found: $removeSegmentId")
        connection().useStatement(
            "DELETE FROM segmentation.segment_members WHERE segment_id = ? AND profile_id IN (SELECT profile_id FROM segmentation.segment_members WHERE segment_id = ?)"
        ) { stmt ->
            stmt.setObject(1, segmentId.toJavaUuid())
            stmt.setObject(2, removeSegmentId.toJavaUuid())
            stmt.executeUpdate()
        }
        val memberCount = memberRepository.getMemberCount(segmentId)
        val result = segmentRepository.updateEvaluation(
            id = segmentId,
            lastEvaluated = OffsetDateTime.now(),
            memberCount = memberCount
        )
        SegmentEvaluated(segmentId, memberCount).dispatch()
        result
    }

    override suspend fun getAudienceProfileIds(segmentIds: List<UUID>): List<UUID> {
        return memberRepository.getProfileIdsBySegments(segmentIds)
    }

    override suspend fun getAudienceProfileIdsPaged(segmentIds: List<UUID>, offset: Long, limit: Int): List<UUID> {
        if (segmentIds.isEmpty()) return emptyList()
        return connection().useStatement(
            "SELECT DISTINCT profile_id FROM segmentation.segment_members WHERE segment_id = ANY(?) ORDER BY profile_id LIMIT ? OFFSET ?"
        ) { stmt ->
            val array = stmt.connection.createArrayOf("uuid", segmentIds.map { it.toJavaUuid() }.toTypedArray())
            stmt.setArray(1, array)
            stmt.setInt(2, limit)
            stmt.setLong(3, offset)
            val rs = stmt.executeQuery()
            val result = mutableListOf<UUID>()
            while (rs.next()) {
                result.add(UUID.parse(rs.getString(1)))
            }
            result
        }
    }

    override suspend fun getAudienceCount(segmentIds: List<UUID>): Long {
        return memberRepository.countProfileIdsBySegments(segmentIds)
    }

    override suspend fun getSegmentsByProfileId(profileId: UUID): List<Segment> {
        val segmentIds = memberRepository.getSegmentIdsByProfileId(profileId)
        if (segmentIds.isEmpty()) return emptyList()
        return segmentRepository.getByIds(segmentIds)
    }

    /**
     * Executes an analytics query and invokes [onPage] for each page of extracted profile IDs.
     * If the query defines `offset` and `limit` parameters (both of type INTEGER),
     * execution is paginated automatically -- the query is called repeatedly with
     * increasing offset values until an empty page is returned, invoking [onPage] per page.
     * Otherwise, the query is executed once and the full result is passed to [onPage].
     * Any stale page fails the surrounding transaction before that page is applied.
     */
    private suspend fun executeQueryPaged(queryId: UUID, onPage: suspend (List<UUID>) -> Unit) {
        val parameters = analyticsQueryService.getParameters(queryId)
        val hasOffset = parameters.any { it.parameter == "offset" && it.type == QueryParameterType.INTEGER }
        val hasLimit = parameters.any { it.parameter == "limit" && it.type == QueryParameterType.INTEGER }
        if (!hasOffset || !hasLimit) {
            val response = analyticsQueryExecutionService.execute(queryId, emptyList())
            requireFresh(response, queryId)
            val profileIds = extractProfileIds(response)
            if (profileIds.isNotEmpty()) {
                onPage(profileIds)
            }
            return
        }
        var offset = 0L
        while (true) {
            val params = listOf(
                AnalyticsQueryExecutionParameterInput("offset", JsonPrimitive(offset)),
                AnalyticsQueryExecutionParameterInput("limit", JsonPrimitive(QUERY_PAGE_SIZE))
            )
            val response = analyticsQueryExecutionService.execute(queryId, params)
            requireFresh(response, queryId)
            val pageIds = extractProfileIds(response)
            if (pageIds.isEmpty()) break
            onPage(pageIds)
            if (pageIds.size < QUERY_PAGE_SIZE) break
            offset += QUERY_PAGE_SIZE
        }
    }

    private fun requireFresh(response: AnalyticsQueryResponse, queryId: UUID) {
        check(!response.stale) {
            "Analytics query $queryId returned stale cached results last refreshed at " +
                "${response.refreshedAt}; segment membership was not updated"
        }
    }

    /**
     * Extracts profile UUIDs from an analytics query response. Looks for a `profile_id`
     * column first, then falls back to the first column value in each row.
     */
    private fun extractProfileIds(response: AnalyticsQueryResponse): List<UUID> {
        return response.records.mapNotNull { row ->
            try {
                val obj = row.jsonObject
                val id = obj["profile_id"]?.jsonPrimitive?.content
                    ?: obj.values.firstOrNull()?.jsonPrimitive?.content
                    ?: return@mapNotNull null
                UUID.parse(id)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Inserts segment members in a single JDBC batch to avoid N individual INSERT round-trips.
     */
    private suspend fun addMembersBatch(segmentId: UUID, profileIds: List<UUID>) {
        if (profileIds.isEmpty()) return
        connection().useStatement(
            "INSERT INTO segmentation.segment_members (segment_id, profile_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
        ) { stmt ->
            val segId = segmentId.toJavaUuid()
            for (profileId in profileIds) {
                stmt.setObject(1, segId)
                stmt.setObject(2, profileId.toJavaUuid())
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    /**
     * Removes segment members in a single JDBC batch to avoid N individual DELETE round-trips.
     */
    private suspend fun removeMembersBatch(segmentId: UUID, profileIds: List<UUID>) {
        if (profileIds.isEmpty()) return
        connection().useStatement(
            "DELETE FROM segmentation.segment_members WHERE segment_id = ? AND profile_id = ?"
        ) { stmt ->
            val segId = segmentId.toJavaUuid()
            for (profileId in profileIds) {
                stmt.setObject(1, segId)
                stmt.setObject(2, profileId.toJavaUuid())
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    private suspend fun createScheduledJob(segmentId: UUID, segmentName: String, cronExpression: String): UUID {
        val job = schedulerService.createJob(
            input = ScheduledJobInput(
                name = "Evaluate segment: $segmentName",
                jobName = "evaluate-segment",
                jobParameters = Json.encodeToJsonElement(EvaluateSegmentJob(segmentId)),
                cronExpression = cronExpression,
                enabled = true,
                allowConcurrent = false,
                catchUp = false,
                maxCatchUp = 1
            ),
            createdBy = UUID.NIL
        )
        return job.id
    }

    private suspend fun syncScheduledJob(
        segmentId: UUID,
        segmentName: String,
        existingJobId: UUID?,
        evaluationSchedule: String?
    ): UUID? {
        if (evaluationSchedule == null) {
            if (existingJobId != null) {
                schedulerService.deleteJob(existingJobId)
            }
            return null
        }
        return if (existingJobId != null) {
            schedulerService.updateJob(
                id = existingJobId,
                input = ScheduledJobInput(
                    name = "Evaluate segment: $segmentName",
                    jobName = "evaluate-segment",
                    jobParameters = Json.encodeToJsonElement(EvaluateSegmentJob(segmentId)),
                    cronExpression = evaluationSchedule,
                    enabled = true,
                    allowConcurrent = false,
                    catchUp = false,
                    maxCatchUp = 1
                )
            )
            existingJobId
        } else {
            createScheduledJob(segmentId, segmentName, evaluationSchedule)
        }
    }

    companion object {
        private const val QUERY_PAGE_SIZE = 10_000
    }
}
