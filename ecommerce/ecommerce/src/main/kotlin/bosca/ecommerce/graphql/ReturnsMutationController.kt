package bosca.ecommerce.graphql

import bosca.ecommerce.model.Return
import bosca.ecommerce.model.ReturnInput
import bosca.ecommerce.service.ReturnService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/**
 * Resolves the `ReturnsMutation` namespace (`Mutation.ecom.returns`): the full return/RMA lifecycle —
 * request, approve, reject, receive, refund. All admin-gated (a return moves money + inventory).
 */
@TypeController
class ReturnsMutationController(
    private val returnService: ReturnService,
    private val groups: GroupEvaluator,
) : GraphQLController<ReturnsMutation> {

    @Field
    suspend fun request(authentication: AuthenticationContext, input: ReturnInput): Return {
        groups.verifyEcomAdmin(authentication)
        return returnService.request(input, authentication.principal()?.id)
    }

    @Field
    suspend fun approve(authentication: AuthenticationContext, id: UUID): Return {
        groups.verifyEcomAdmin(authentication)
        return returnService.approve(id, authentication.principal()?.id)
    }

    @Field
    suspend fun reject(authentication: AuthenticationContext, id: UUID, reason: String? = null): Return {
        groups.verifyEcomAdmin(authentication)
        return returnService.reject(id, reason, authentication.principal()?.id)
    }

    @Field
    suspend fun receive(authentication: AuthenticationContext, id: UUID): Return {
        groups.verifyEcomAdmin(authentication)
        return returnService.receive(id, authentication.principal()?.id)
    }

    @Field
    suspend fun refund(authentication: AuthenticationContext, id: UUID): Return {
        groups.verifyEcomAdmin(authentication)
        return returnService.refund(id, authentication.principal()?.id)
    }
}
