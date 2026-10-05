package bosca.segmentation.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.segmentation.model.Segment
import bosca.segmentation.service.SegmentService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object Segments

@TypeController
class SegmentsController(
    private val segmentService: SegmentService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Segments> {

    @Field
    suspend fun all(authentication: AuthenticationContext, offset: Long, limit: Int): List<Segment> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return segmentService.getAll(maxOf(offset, 0), limit.coerceIn(1, 100))
    }

    @Field
    suspend fun segment(authentication: AuthenticationContext, id: UUID): Segment? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return segmentService.getById(id)
    }

    @Field
    suspend fun segmentsByProfile(authentication: AuthenticationContext, profileId: UUID): List<Segment> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return segmentService.getSegmentsByProfileId(profileId)
    }
}
