package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.LinkChallenge
import bosca.security.model.LinkProofMethod

@TypeController
class LinkChallengeController : GraphQLController<LinkChallenge> {

    @Field
    fun token(challenge: LinkChallenge): String = challenge.token

    @Field
    fun methods(challenge: LinkChallenge): List<LinkProofMethod> = challenge.methods
}
