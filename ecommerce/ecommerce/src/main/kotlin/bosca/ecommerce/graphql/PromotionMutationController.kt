package bosca.ecommerce.graphql

import bosca.ecommerce.model.Promotion
import bosca.ecommerce.model.PromotionInput
import bosca.ecommerce.service.PromotionService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** Mutations on one promotion. Admin-gated. */
@TypeController
class PromotionMutationController(
    private val promotionService: PromotionService,
    private val groups: GroupEvaluator,
) : GraphQLController<PromotionMutation> {

    @Field
    suspend fun edit(authentication: AuthenticationContext, source: PromotionMutation, input: PromotionInput): Promotion {
        groups.verifyEcomAdmin(authentication)
        return promotionService.edit(source.id, input, authentication.principal()?.id)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, source: PromotionMutation): Boolean {
        groups.verifyEcomAdmin(authentication)
        promotionService.delete(source.id, authentication.principal()?.id)
        return true
    }
}
