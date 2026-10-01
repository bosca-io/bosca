package bosca.ecommerce.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/*
 * The ecom enum vocabulary. Each Kotlin enum:
 *  - has UPPERCASE constants (the GraphQL enum values and the kotlinx-serialized JSON form),
 *  - carries `@DbMapper(...)` pointing at an `EnumMapper` that bridges to the native Postgres enum
 *    (which uses lowercase labels — EnumMapper lowercases on bind, uppercases on read),
 *  - has NO `@SerialName`: the Kotlin name IS the serialized name (verified workspace convention).
 *
 * In `@Query` SQL these are bound with the Postgres cast `:field::ecom.<enum_type>` at every
 * insert/update/where site (the enum_type name may differ from the Kotlin name, e.g.
 * [PlanStatus] -> `ecom.subscription_plan_status`).
 */

/** What kind of thing a product is; drives fulfillment behavior. `ecom.product_type`. */
@Serializable
@DbMapper(ProductTypeMapper::class)
enum class ProductType { PHYSICAL, VIRTUAL, SERVICE, SHIPPING, PROMOTION, SUBSCRIPTION }

object ProductTypeMapper : EnumMapper<ProductType>({ ProductType.valueOf(it.uppercase()) })

/** Whether a store is an online (VIRTUAL) or in-person (PHYSICAL) selling context. `ecom.store_type`. */
@Serializable
@DbMapper(StoreTypeMapper::class)
enum class StoreType { VIRTUAL, PHYSICAL }

object StoreTypeMapper : EnumMapper<StoreType>({ StoreType.valueOf(it.uppercase()) })

/** Address role: where the bill goes vs. where the goods go. `ecom.address_type`. */
@Serializable
@DbMapper(AddressTypeMapper::class)
enum class AddressType { BILLING, SHIPPING }

object AddressTypeMapper : EnumMapper<AddressType>({ AddressType.valueOf(it.uppercase()) })

/** Billing account classification. `ecom.account_type`. */
@Serializable
@DbMapper(AccountTypeMapper::class)
enum class AccountType { CONSUMER, BUSINESS }

object AccountTypeMapper : EnumMapper<AccountType>({ AccountType.valueOf(it.uppercase()) })

/** How a payment is tendered. `ecom.payment_type`. */
@Serializable
@DbMapper(PaymentTypeMapper::class)
enum class PaymentType { CASH, CHECK, CREDIT_CARD, ACCOUNT_CREDIT, COMPANY_CREDIT }

object PaymentTypeMapper : EnumMapper<PaymentType>({ PaymentType.valueOf(it.uppercase()) })

/** What a payment record represents. `ecom.transaction_type`. */
@Serializable
@DbMapper(TransactionTypeMapper::class)
enum class TransactionType { PAYMENT, REFUND, REFUND_TO_CHECK, REFUND_TO_ACCOUNT_CREDIT, VOID }

object TransactionTypeMapper : EnumMapper<TransactionType>({ TransactionType.valueOf(it.uppercase()) })

/** Recurring-purchase lifecycle. `ecom.subscription_status`. */
@Serializable
@DbMapper(SubscriptionStatusMapper::class)
enum class SubscriptionStatus {
    PENDING, ACTIVE, INACTIVE, TRIALING, CANCELLED, PAST_DUE, UNPAID, EXPIRED, DELETED
}

object SubscriptionStatusMapper : EnumMapper<SubscriptionStatus>({ SubscriptionStatus.valueOf(it.uppercase()) })

/** Whether a plan can accept new subscriptions. `ecom.subscription_plan_status`. */
@Serializable
@DbMapper(PlanStatusMapper::class)
enum class PlanStatus { ACTIVE, INACTIVE, DELETED }

object PlanStatusMapper : EnumMapper<PlanStatus>({ PlanStatus.valueOf(it.uppercase()) })

/** Unit for plan/subscription renewal intervals. `ecom.subscription_interval_unit`. */
@Serializable
@DbMapper(IntervalUnitMapper::class)
enum class IntervalUnit { SECONDS, MINUTES, HOURS, DAYS, MONTHS, YEARS }

object IntervalUnitMapper : EnumMapper<IntervalUnit>({ IntervalUnit.valueOf(it.uppercase()) })

/** Where a promotion's rule applies: cart pricing or subscription renewal pricing. `ecom.promotion_type`. */
@Serializable
@DbMapper(PromotionTypeMapper::class)
enum class PromotionType { CART, SUBSCRIPTION }

object PromotionTypeMapper : EnumMapper<PromotionType>({ PromotionType.valueOf(it.uppercase()) })

/**
 * A [Shipment]'s single lifecycle, from our dispatch through the carrier's delivery. `ecom.shipment_status`.
 * The first five are the forward timeline (we set AWAITING/SHIPPED; the carrier advances the rest via
 * tracking); RETURNED/FAILURE are carrier-reported terminal off-ramps and CANCELLED is our admin
 * override. [lifecycleRank] orders the timeline so a carrier tracking update can only ever move it
 * forward (never regress on a late/out-of-order callback).
 */
@Serializable
@DbMapper(ShipmentStatusMapper::class)
enum class ShipmentStatus(val lifecycleRank: Int) {
    /** Items can't be boxed yet (no container fits / none defined) — blocked until packable. Never ships. */
    UNABLE_TO_PACKAGE(0),
    AWAITING(0),
    SHIPPED(1),
    IN_TRANSIT(2),
    OUT_FOR_DELIVERY(3),
    DELIVERED(4),
    RETURNED(5),
    FAILURE(5),
    CANCELLED(6),
    ;

    /** True once the shipment has been handed to the carrier (any carrier-lifecycle state). */
    val isDispatched: Boolean get() = this == SHIPPED || this == IN_TRANSIT || this == OUT_FOR_DELIVERY ||
        this == DELIVERED || this == RETURNED || this == FAILURE

    /** True once the shipment can no longer progress on its own (delivered, returned, failed, or cancelled). */
    val isTerminal: Boolean get() = this == DELIVERED || this == RETURNED || this == FAILURE || this == CANCELLED
}

object ShipmentStatusMapper : EnumMapper<ShipmentStatus>({ ShipmentStatus.valueOf(it.uppercase()) })

/**
 * A return/RMA lifecycle. `ecom.return_status`. Forward path: REQUESTED → APPROVED → RECEIVED →
 * REFUNDED (restock + refund fire at REFUNDED); REJECTED is the admin off-ramp from REQUESTED/APPROVED.
 */
@Serializable
@DbMapper(ReturnStatusMapper::class)
enum class ReturnStatus { REQUESTED, APPROVED, RECEIVED, REFUNDED, REJECTED }

object ReturnStatusMapper : EnumMapper<ReturnStatus>({ ReturnStatus.valueOf(it.uppercase()) })

/** The linear unit a company's product/container dimensions are stored in. `ecom.length_unit`. */
@Serializable
@DbMapper(LengthUnitMapper::class)
enum class LengthUnit { INCHES, CENTIMETERS }

object LengthUnitMapper : EnumMapper<LengthUnit>({ LengthUnit.valueOf(it.uppercase()) })

/** The weight unit a company's product/container weights are stored in. `ecom.weight_unit`. */
@Serializable
@DbMapper(WeightUnitMapper::class)
enum class WeightUnit { POUNDS, KILOGRAMS }

object WeightUnitMapper : EnumMapper<WeightUnit>({ WeightUnit.valueOf(it.uppercase()) })
