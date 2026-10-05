package bosca.profile.rating.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.rating.model.ProfileRating

@TypeController
class ProfileRatingController : GraphQLController<ProfileRating> {

    @Field
    fun id(rating: ProfileRating) = rating.id

    @Field
    fun rating(rating: ProfileRating) = rating.rating

    @Field
    fun created(rating: ProfileRating) = rating.created
}
