package bosca.community.graphql

import bosca.community.model.CommunityGroupSignupToken
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class CommunityGroupSignupTokenController : GraphQLController<CommunityGroupSignupToken> {

    @Field
    fun token(token: CommunityGroupSignupToken) = token.token

    @Field
    fun groupId(token: CommunityGroupSignupToken) = token.groupId

    @Field
    fun created(token: CommunityGroupSignupToken) = token.created

    @Field
    fun expires(token: CommunityGroupSignupToken) = token.expires
}
