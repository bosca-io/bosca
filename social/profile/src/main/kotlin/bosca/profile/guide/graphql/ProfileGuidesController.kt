package bosca.profile.guide.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import kotlinx.serialization.Serializable

@Serializable
class ProfileGuides(val profile: Profile)

@TypeController
class ProfileGuidesController : GraphQLController<ProfileGuides> {

    @Field
    fun progressions(guides: ProfileGuides) = ProfileGuideProgressions(guides.profile)

    @Field
    fun history(guides: ProfileGuides) = ProfileGuideHistories(guides.profile)
}
