package bosca.community.graphql

import bosca.community.model.CommunityGroupMember
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService

@TypeController
class CommunityGroupMemberController(
    private val profileService: ProfileService
) : GraphQLController<CommunityGroupMember> {

    @Field
    suspend fun profile(member: CommunityGroupMember): Profile {
        return profileService.getById(member.profileId)
    }
}
