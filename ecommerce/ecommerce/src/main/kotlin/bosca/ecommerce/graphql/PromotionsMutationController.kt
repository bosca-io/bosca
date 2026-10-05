package bosca.ecommerce.graphql

import bosca.ecommerce.model.Promotion
import bosca.ecommerce.model.PromotionInput
import bosca.ecommerce.service.PromotionService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/** Promotion admin CRUD under `EcomMutation.promotions`. Admin-gated. */
@TypeController
class PromotionsMutationController(
    private val promotionService: PromotionService,
    private val groups: GroupEvaluator,
) : GraphQLController<PromotionsMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, input: PromotionInput): Promotion {
        groups.verifyEcomAdmin(authentication)
        return promotionService.create(input, authentication.principal()?.id)
    }

    @Field
    fun promotion(id: UUID): PromotionMutation = PromotionMutation(id)
}
