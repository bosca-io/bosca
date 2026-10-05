package bosca.segmentation.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.segmentation.model.SegmentMember
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Resolves fields on the SegmentMember GraphQL type, including the associated profile.
 */
@TypeController(type = "SegmentMember")
class SegmentMemberController(
    private val profileService: ProfileService
) : GraphQLController<SegmentMember> {

    @Field
    fun segmentId(member: SegmentMember): UUID = member.segmentId

    @Field
    fun profileId(member: SegmentMember): UUID = member.profileId

    @Field
    suspend fun profile(member: SegmentMember): Profile? {
        return try {
            profileService.getById(member.profileId)
        } catch (_: NoSuchElementException) {
            null
        }
    }

    @Field
    fun addedAt(member: SegmentMember): OffsetDateTime = member.addedAt
}
