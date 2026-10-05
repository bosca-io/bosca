package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.LoginResponse
import bosca.security.service.SecurityService
import bosca.server.ServerCall

object LinkMutation

/**
 * GraphQL mutations for completing an account link after a sign-up collided with an existing verified
 * account. Each takes the single-use pending-link token issued when the collision was detected; the
 * token is the bearer of the pending intent, and the password / emailed proof is the ownership check.
 */
@TypeController
class LinkMutationController(
    private val securityService: SecurityService,
) : GraphQLController<LinkMutation> {

    /** Re-auth proof: confirm by entering the existing account's password. */
    @Field
    suspend fun confirmPassword(token: String, password: String): LoginResponse =
        securityService.confirmAccountLinkWithPassword(token, password)

    /** Send a one-time magic-link to the existing account's verified email (works for OAuth-only accounts). */
    @Field
    suspend fun requestEmailProof(token: String, call: ServerCall): Boolean {
        // Carry the host the user is on so the magic-link routes back to it (multi-host); the service
        // validates it against the allow-list and falls back to the default app origin otherwise. appOrigin
        // reads the browser Origin/Referer, not the addressed API host, so a proxied call names the app host.
        securityService.requestAccountLinkEmailProof(token, call.request.appOrigin)
        return true
    }

    /** Complete the link using the one-time token delivered by [requestEmailProof]. */
    @Field
    suspend fun confirmEmail(proofToken: String): LoginResponse =
        securityService.confirmAccountLinkWithEmail(proofToken)
}
