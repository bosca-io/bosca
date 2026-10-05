package bosca.ecommerce.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

/**
 * Declares the GraphQL schema files the ecommerce module contributes. KSP processes this to
 * generate `EcommerceSchemaRegistrar`, which the server merges into the runtime schema. As each
 * domain area lands it adds its own `@Schema("…")` file here.
 */
@Schemas
interface SchemaRegistrar {

    /** The `ecom` namespace root: `Money` scalar, `Query.ecom` / `Mutation.ecom`. */
    @Schema("ecom.graphqls")
    val ecom: String

    /**
     * The polymorphic configuration/extras unions shared across the ecom types. The file is named
     * `commerce-configurations.graphqls` (not `configurations.graphqls`) because schema resources are
     * loaded by classpath path `graphql/<name>` — a bare `configurations.graphqls` collides with
     * bosca-core's configuration module and both registrars would load the same file.
     */
    @Schema("commerce-configurations.graphqls")
    val configurations: String

    /** Company + CompanyCredit types and the companies mutation namespace. */
    @Schema("companies.graphqls")
    val companies: String

    /** Customer + Account + AccountAddress types and their mutation namespaces. */
    @Schema("customers.graphqls")
    val customers: String

    /** Catalog + Manufacturer types and their mutation namespaces. */
    @Schema("catalog.graphqls")
    val catalog: String

    /** Product type (content-Metadata-backed) and its mutation namespace. */
    @Schema("product.graphqls")
    val product: String

    /** CatalogProduct (sellable entries) + Catalog.products, and the mutation namespace. */
    @Schema("catalogproduct.graphqls")
    val catalogProduct: String

    /** Store + PaymentProvider/ShippingProvider types and their mutation namespaces. */
    @Schema("store.graphqls")
    val store: String

    /** Inventory + FulfillmentCenter types, Product.inventory, and the fulfillment mutation namespace. */
    @Schema("inventory.graphqls")
    val inventory: String

    /** Cart + CartItem + Tax + CartSubmitResult, CartStatusFlag, and the carts mutation namespace. */
    @Schema("cart.graphqls")
    val cart: String

    /** Promotion + the polymorphic Rule union, admin CRUD, and the cart apply/remove-code mutations. */
    @Schema("promotion.graphqls")
    val promotion: String

    /** ShippingRate type + the shippingRates query and the setShipping cart mutation. */
    @Schema("shipping.graphqls")
    val shipping: String

    /** Payment type + enums, admin refund/void mutations, and SubmitPaymentInput. */
    @Schema("payment.graphqls")
    val payment: String

    /** Subscription/Plan/PlanGroup types + enums, admin CRUD, subscribe, and the SubscriptionRule union members. */
    @Schema("subscription.graphqls")
    val subscription: String

    /** The EcomAudit read type + the filterable `Ecom.audit` log query. Named to avoid classpath/type collisions. */
    @Schema("ecom-audit.graphqls")
    val audit: String

    /** EcomShipment type + ShipmentStatus + the FulfillmentMutation.shipment(id) { ship } namespace. Named to avoid collisions. */
    @Schema("ecom-shipment.graphqls")
    val shipment: String

    /** EcomContainer (box-catalog) type + the containers read/admin-CRUD namespace. Named to avoid collisions. */
    @Schema("ecom-container.graphqls")
    val container: String

    /** EcomReturn type + ReturnStatus + the returns request/approve/reject/receive/refund namespace. Named to avoid collisions. */
    @Schema("ecom-return.graphqls")
    val ecomReturn: String

    /** IapMutation.redeem + the IapRedeemInput/IapRedemptionResult types and IAP enums. */
    @Schema("iap.graphqls")
    val iap: String
}
