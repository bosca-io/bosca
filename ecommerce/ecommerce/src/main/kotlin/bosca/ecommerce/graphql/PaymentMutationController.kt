package bosca.ecommerce.graphql

import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Payment
import bosca.ecommerce.service.PaymentService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** Refund/void operations on one payment. Admin-gated. */
@TypeController
class PaymentMutationController(
    private val paymentService: PaymentService,
    private val groups: GroupEvaluator,
) : GraphQLController<PaymentMutation> {

    @Field
    suspend fun refund(authentication: AuthenticationContext, source: PaymentMutation, amount: Money, reason: String?): Payment {
        groups.verifyEcomAdmin(authentication)
        return paymentService.refund(source.id, amount, reason, authentication.principal()?.id)
    }

    @Field
    suspend fun refundToAccountCredit(authentication: AuthenticationContext, source: PaymentMutation, amount: Money, reason: String?): Payment {
        groups.verifyEcomAdmin(authentication)
        return paymentService.refundToAccountCredit(source.id, amount, reason, authentication.principal()?.id)
    }

    @Field
    suspend fun void(authentication: AuthenticationContext, source: PaymentMutation, reason: String?): Payment {
        groups.verifyEcomAdmin(authentication)
        return paymentService.void(source.id, reason, authentication.principal()?.id)
    }
}
