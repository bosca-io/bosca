package bosca.ecommerce.graphql

import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Payment
import bosca.ecommerce.model.PaymentType
import bosca.ecommerce.model.TransactionType
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Field wiring for the `Payment` GraphQL type. Provider evidence is tokens/ids only — never a PAN. */
@TypeController
class PaymentController : GraphQLController<Payment> {

    @Field fun id(source: Payment): UUID = source.id
    @Field fun transactionType(source: Payment): TransactionType = source.transactionType
    @Field fun type(source: Payment): PaymentType = source.type
    @Field fun parentId(source: Payment): UUID? = source.parentId
    @Field fun amount(source: Payment): Money = source.amount
    @Field fun currency(source: Payment): String = source.currency
    @Field fun nonRefundableAmount(source: Payment): Money = source.nonRefundableAmount
    @Field fun refundedAmount(source: Payment): Money = source.refundedAmount
    @Field fun complete(source: Payment): Boolean = source.complete
    @Field fun confirmed(source: Payment): Boolean = source.confirmed
    @Field fun voided(source: Payment): OffsetDateTime? = source.voided
    @Field fun voidedReason(source: Payment): String? = source.voidedReason
    @Field fun refunded(source: Payment): OffsetDateTime? = source.refunded
    @Field fun refundReason(source: Payment): String? = source.refundReason
    @Field fun note(source: Payment): String? = source.note
    @Field fun providerTransactionId(source: Payment): String? = source.providerTransactionId
    @Field fun providerStatus(source: Payment): String? = source.providerStatus
    @Field fun created(source: Payment): OffsetDateTime = source.created
    @Field fun modified(source: Payment): OffsetDateTime = source.modified
}
