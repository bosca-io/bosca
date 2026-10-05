package bosca.segmentation.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.pipelines.service.PipelineService
import bosca.segmentation.jobs.RunPipelineForSegmentJob
import bosca.segmentation.jobs.enqueue
import bosca.segmentation.model.Segment
import bosca.segmentation.model.SegmentInput
import bosca.segmentation.service.SegmentService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object SegmentsMutation

@TypeController
class SegmentsMutationController(
    private val segmentService: SegmentService,
    private val pipelineService: PipelineService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<SegmentsMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, segment: SegmentInput): Segment {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return segmentService.add(segment)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, id: UUID, segment: SegmentInput): Segment {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return segmentService.edit(id, segment)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        segmentService.delete(id)
        return true
    }

    @Field
    suspend fun addMembers(authentication: AuthenticationContext, segmentId: UUID, profileIds: List<UUID>): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        segmentService.addMembers(segmentId, profileIds)
        return true
    }

    @Field
    suspend fun removeMembers(authentication: AuthenticationContext, segmentId: UUID, profileIds: List<UUID>): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        segmentService.removeMembers(segmentId, profileIds)
        return true
    }

    @Field
    suspend fun evaluate(authentication: AuthenticationContext, segmentId: UUID): Segment {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return segmentService.evaluate(segmentId)
    }

    @Field
    suspend fun populateFromQuery(authentication: AuthenticationContext, segmentId: UUID, analyticsQueryId: UUID): Segment {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return segmentService.populateFromQuery(segmentId, analyticsQueryId)
    }

    @Field
    suspend fun removeFromQuery(authentication: AuthenticationContext, segmentId: UUID, analyticsQueryId: UUID): Segment {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return segmentService.removeFromQuery(segmentId, analyticsQueryId)
    }

    @Field
    suspend fun removeFromSegment(authentication: AuthenticationContext, segmentId: UUID, removeSegmentId: UUID): Segment {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return segmentService.removeFromSegment(segmentId, removeSegmentId)
    }

    @Field
    suspend fun runPipeline(authentication: AuthenticationContext, segmentId: UUID, pipelineId: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        val principalId = authentication.principal()?.id ?: error("Authenticated principal required")
        segmentService.getById(segmentId) ?: error("Segment not found: $segmentId")
        pipelineService.get(pipelineId) ?: error("Pipeline not found: $pipelineId")
        RunPipelineForSegmentJob(
            segmentId = segmentId,
            pipelineId = pipelineId,
            principalId = principalId,
        ).enqueue()
        return true
    }
}
