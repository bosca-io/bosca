package bosca.ecommerce.graphql

import bosca.ecommerce.model.AddCartItemInput
import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.CartSubmitResult
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.RefundItemInput
import bosca.ecommerce.model.RefundTender
import bosca.ecommerce.model.SetCartAddressInput
import bosca.ecommerce.model.SubmitPaymentInput
import bosca.ecommerce.service.CartService
import bosca.ecommerce.service.PromotionService
import bosca.ecommerce.service.ShippingService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/**
 * Mutations on one cart. Buyer-facing operations authorize the caller (owner or ecom administrator);
 * the order-admin operations (complete/cancel/refund) are ecom-administrator only.
 */
@TypeController
class CartMutationController(
    private val cartService: CartService,
    private val promotionService: PromotionService,
    private val shippingService: ShippingService,
    private val cartAccess: CartAccessEvaluator,
    private val groups: GroupEvaluator,
) : GraphQLController<CartMutation> {

    @Field
    suspend fun addItem(authentication: AuthenticationContext, source: CartMutation, input: AddCartItemInput): Cart {
        authorize(authentication, source.id)
        return cartService.addItem(source.id, input, authentication.principal()?.id)
    }

    @Field
    suspend fun updateItem(authentication: AuthenticationContext, source: CartMutation, itemId: UUID, quantity: Int): Cart {
        authorize(authentication, source.id)
        return cartService.updateItemQuantity(source.id, itemId, quantity, authentication.principal()?.id)
    }

    @Field
    suspend fun removeItem(authentication: AuthenticationContext, source: CartMutation, itemId: UUID): Cart {
        authorize(authentication, source.id)
        return cartService.removeItem(source.id, itemId, authentication.principal()?.id)
    }

    @Field
    suspend fun setAddress(authentication: AuthenticationContext, source: CartMutation, input: SetCartAddressInput): CartAddress {
        authorize(authentication, source.id)
        return cartService.setAddress(source.id, input, authentication.principal()?.id)
    }

    @Field
    suspend fun removeAddress(authentication: AuthenticationContext, source: CartMutation, type: AddressType): Cart {
        authorize(authentication, source.id)
        return cartService.removeAddress(source.id, type, authentication.principal()?.id)
    }

    @Field
    suspend fun setBillingSameAsShipping(authentication: AuthenticationContext, source: CartMutation, value: Boolean): Cart {
        authorize(authentication, source.id)
        return cartService.setBillingSameAsShipping(source.id, value, authentication.principal()?.id)
    }

    @Field
    suspend fun applyCode(authentication: AuthenticationContext, source: CartMutation, code: String): Cart {
        authorize(authentication, source.id)
        return promotionService.applyToCart(source.id, code, authentication.principal()?.id)
    }

    @Field
    suspend fun removeCode(authentication: AuthenticationContext, source: CartMutation, code: String): Cart {
        authorize(authentication, source.id)
        return promotionService.removeFromCart(source.id, code, authentication.principal()?.id)
    }

    @Field
    suspend fun setShipping(authentication: AuthenticationContext, source: CartMutation, token: String): Cart {
        authorize(authentication, source.id)
        return shippingService.selectRate(source.id, token, authentication.principal()?.id)
    }

    @Field
    suspend fun submit(authentication: AuthenticationContext, source: CartMutation, payment: SubmitPaymentInput?): CartSubmitResult {
        authorize(authentication, source.id)
        return cartService.submit(source.id, payment, authentication.principal()?.id)
    }

    @Field
    suspend fun complete(authentication: AuthenticationContext, source: CartMutation): Cart {
        groups.verifyEcomAdmin(authentication)
        return cartService.complete(source.id, authentication.principal()?.id)
    }

    @Field
    suspend fun cancel(authentication: AuthenticationContext, source: CartMutation): Cart {
        groups.verifyEcomAdmin(authentication)
        return cartService.cancel(source.id, authentication.principal()?.id)
    }

    @Field
    suspend fun refundItems(
        authentication: AuthenticationContext,
        source: CartMutation,
        items: List<RefundItemInput>,
        tender: RefundTender,
        checkNumber: String?,
        reason: String?,
    ): Cart {
        groups.verifyEcomAdmin(authentication)
        return cartService.refundItems(source.id, items, tender, checkNumber, reason, authentication.principal()?.id)
    }

    @Field
    suspend fun confirmCheck(authentication: AuthenticationContext, source: CartMutation, paymentId: UUID, checkNumber: String?): Cart {
        groups.verifyEcomAdmin(authentication)
        return cartService.confirmCheck(source.id, paymentId, checkNumber, authentication.principal()?.id)
    }

    @Field
    suspend fun setLocked(authentication: AuthenticationContext, source: CartMutation, locked: Boolean): Cart {
        groups.verifyEcomAdmin(authentication)
        return cartService.setLocked(source.id, locked, authentication.principal()?.id)
    }

    @Field
    suspend fun setItemPrice(authentication: AuthenticationContext, source: CartMutation, itemId: UUID, retailPrice: Money, salesPrice: Money): Cart {
        groups.verifyEcomAdmin(authentication)
        return cartService.setItemPrice(source.id, itemId, retailPrice, salesPrice, authentication.principal()?.id)
    }

    private suspend fun authorize(authentication: AuthenticationContext, cartId: UUID) {
        val cart = cartService.get(cartId) ?: error("cart $cartId not found")
        cartAccess.verify(authentication, cart)
    }
}
