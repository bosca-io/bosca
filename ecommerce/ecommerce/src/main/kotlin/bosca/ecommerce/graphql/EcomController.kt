package bosca.ecommerce.graphql

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.Audit
import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Container
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.Inventory
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Payment
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.Promotion
import bosca.ecommerce.model.Return
import bosca.ecommerce.model.ReturnStatus
import bosca.ecommerce.model.Shipment
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.model.ShippingRate
import bosca.ecommerce.model.Store
import bosca.ecommerce.model.Subscription
import bosca.ecommerce.model.SubscriptionPlan
import bosca.ecommerce.model.SubscriptionPlanGroup
import bosca.ecommerce.service.AccountService
import bosca.ecommerce.service.CartService
import bosca.ecommerce.service.CatalogProductService
import bosca.ecommerce.service.CatalogService
import bosca.ecommerce.service.ContainerService
import bosca.ecommerce.service.CompanyService
import bosca.ecommerce.service.CustomerService
import bosca.ecommerce.service.EcomAuditService
import bosca.ecommerce.service.FulfillmentService
import bosca.ecommerce.service.InventoryService
import bosca.ecommerce.service.ManufacturerService
import bosca.ecommerce.service.PaymentService
import bosca.ecommerce.service.ProductService
import bosca.ecommerce.service.PromotionService
import bosca.ecommerce.service.ProviderService
import bosca.ecommerce.service.ReturnService
import bosca.ecommerce.service.ShipmentService
import bosca.ecommerce.service.ShippingService
import bosca.ecommerce.service.StoreService
import bosca.ecommerce.service.SubscriptionPlanService
import bosca.ecommerce.service.SubscriptionService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/**
 * Resolves the [Ecom] query namespace (`Query.ecom`). Admin/PII reads (the companies list, customer
 * and account by id) require the administrators group; `myCustomer` resolves the caller's own
 * customer (principal -> profile). Merchandising reads (company/catalog/manufacturer/product) are
 * open for now; layers the full per-company permission model + storefront visibility.
 */
@TypeController
class EcomController(
    private val companyService: CompanyService,
    private val customerService: CustomerService,
    private val accountService: AccountService,
    private val catalogService: CatalogService,
    private val catalogProductService: CatalogProductService,
    private val manufacturerService: ManufacturerService,
    private val productService: ProductService,
    private val storeService: StoreService,
    private val providerService: ProviderService,
    private val inventoryService: InventoryService,
    private val fulfillmentService: FulfillmentService,
    private val cartService: CartService,
    private val shipmentService: ShipmentService,
    private val returnService: ReturnService,
    private val containerService: ContainerService,
    private val cartAccess: CartAccessEvaluator,
    private val promotionService: PromotionService,
    private val shippingService: ShippingService,
    private val paymentService: PaymentService,
    private val auditService: EcomAuditService,
    private val subscriptionPlanService: SubscriptionPlanService,
    private val subscriptionService: SubscriptionService,
    private val profileService: ProfileService,
    private val groups: GroupEvaluator,
) : GraphQLController<Ecom> {

    @Field
    suspend fun company(id: UUID): Company? = companyService.get(id)

    @Field
    suspend fun companies(authentication: AuthenticationContext, offset: Int, limit: Int): List<Company> {
        groups.verifyEcomAdmin(authentication)
        return companyService.getAll(offset, limit)
    }

    @Field
    suspend fun customer(authentication: AuthenticationContext, id: UUID): Customer? {
        groups.verifyEcomAdmin(authentication)
        return customerService.get(id)
    }

    /** A page of a company's customers, newest first (PII — admin). */
    @Field
    suspend fun customers(authentication: AuthenticationContext, companyId: UUID, offset: Int, limit: Int): List<Customer> {
        groups.verifyEcomAdmin(authentication)
        return customerService.getByCompany(companyId, offset, limit)
    }

    /** The caller's customer record in a company, resolved principal -> profile -> customer. */
    @Field
    suspend fun myCustomer(authentication: AuthenticationContext, companyId: UUID): Customer? {
        val principal = authentication.principal()?.asPrincipal() ?: return null
        val profile = profileService.getPrimaryProfile(principal) ?: return null
        return customerService.getByProfile(companyId, profile.id)
    }

    @Field
    suspend fun account(authentication: AuthenticationContext, id: UUID): Account? {
        groups.verifyEcomAdmin(authentication)
        return accountService.get(id)
    }

    /** A page of a company's billing accounts, newest first. Admin. */
    @Field
    suspend fun accounts(authentication: AuthenticationContext, companyId: UUID, offset: Int, limit: Int): List<Account> {
        groups.verifyEcomAdmin(authentication)
        return accountService.getByCompany(companyId, offset, limit)
    }

    @Field
    suspend fun catalog(id: UUID): Catalog? = catalogService.get(id)

    @Field
    suspend fun catalogByKey(companyId: UUID, key: String): Catalog? = catalogService.getByKey(companyId, key)

    @Field
    suspend fun catalogs(companyId: UUID): List<Catalog> = catalogService.getByCompany(companyId)

    @Field
    suspend fun manufacturer(id: UUID): Manufacturer? = manufacturerService.get(id)

    @Field
    suspend fun manufacturers(companyId: UUID, offset: Int, limit: Int): List<Manufacturer> =
        manufacturerService.getByCompany(companyId, offset, limit)

    @Field
    suspend fun product(id: UUID): Product? = productService.get(id)

    @Field
    suspend fun products(companyId: UUID, offset: Int, limit: Int): List<Product> =
        productService.getByCompany(companyId, offset, limit)

    @Field
    suspend fun catalogProduct(id: UUID): CatalogProduct? = catalogProductService.get(id)

    @Field
    suspend fun catalogProducts(
        catalogId: UUID,
        type: ProductType?,
        activeOnly: Boolean,
        offset: Int,
        limit: Int,
    ): List<CatalogProduct> = catalogProductService.getByCatalog(catalogId, type, activeOnly, offset, limit)

    @Field
    suspend fun store(id: UUID): Store? = storeService.get(id)

    @Field
    suspend fun storeByIdentifier(identifier: String): Store? = storeService.getByIdentifier(identifier)

    @Field
    suspend fun stores(companyId: UUID): List<Store> = storeService.getByCompany(companyId)

    /**
     * A company's payment providers. The list itself is merchandising-safe; each row's secret-bearing
     * [PaymentProviderController.configuration] stays admin-gated at the type level.
     */
    @Field
    suspend fun paymentProviders(companyId: UUID): List<PaymentProvider> =
        providerService.getPaymentProvidersByCompany(companyId)

    /** A company's shipping providers (configuration admin-gated at the type level, as with payments). */
    @Field
    suspend fun shippingProviders(companyId: UUID): List<ShippingProvider> =
        providerService.getShippingProvidersByCompany(companyId)

    /** The DI-registered payment provider implementation keys — valid `providerKey` values. Admin. */
    @Field
    suspend fun paymentProviderKeys(authentication: AuthenticationContext): List<String> {
        groups.verifyEcomAdmin(authentication)
        return providerService.paymentProviderKeys()
    }

    /** The DI-registered shipping provider implementation keys — valid `providerKey` values. Admin. */
    @Field
    suspend fun shippingProviderKeys(authentication: AuthenticationContext): List<String> {
        groups.verifyEcomAdmin(authentication)
        return providerService.shippingProviderKeys()
    }

    /** Inventory rows for a product across fulfillment centers (operational data — admin). */
    @Field
    suspend fun inventory(authentication: AuthenticationContext, productId: UUID): List<Inventory> {
        groups.verifyEcomAdmin(authentication)
        return inventoryService.getByProduct(productId)
    }

    @Field
    suspend fun fulfillmentCenter(authentication: AuthenticationContext, id: UUID): FulfillmentCenter? {
        groups.verifyEcomAdmin(authentication)
        return fulfillmentService.getCenter(id)
    }

    /** A company's fulfillment centers (operational data — admin). */
    @Field
    suspend fun fulfillmentCenters(authentication: AuthenticationContext, companyId: UUID): List<FulfillmentCenter> {
        groups.verifyEcomAdmin(authentication)
        return fulfillmentService.getCentersByCompany(companyId)
    }

    /** A cart by id, readable only by its owner (customer -> profile) or an ecom administrator. */
    @Field
    suspend fun cart(authentication: AuthenticationContext, id: UUID): Cart? {
        val cart = cartService.get(id) ?: return null
        cartAccess.verify(authentication, cart)
        return cart
    }

    /** A store's carts, newest first (operational inspector — admin). */
    @Field
    suspend fun carts(authentication: AuthenticationContext, storeId: UUID, offset: Int, limit: Int): List<Cart> {
        groups.verifyEcomAdmin(authentication)
        return cartService.getByStore(storeId, offset, limit)
    }

    /** A store's carts filtered to those with [status] set, newest first. Admin. */
    @Field
    suspend fun cartsByStatus(
        authentication: AuthenticationContext,
        storeId: UUID,
        status: bosca.ecommerce.model.CartStatusFlag,
        offset: Int,
        limit: Int,
    ): List<Cart> {
        groups.verifyEcomAdmin(authentication)
        return cartService.getByStoreAndStatus(storeId, status, offset, limit)
    }

    /** A store's orders (paid carts), newest first — the fulfillment/orders view. Admin. */
    @Field
    suspend fun orders(authentication: AuthenticationContext, storeId: UUID, offset: Int, limit: Int): List<Cart> {
        groups.verifyEcomAdmin(authentication)
        return cartService.getOrdersByStore(storeId, offset, limit)
    }

    /** The shipments fulfilling an order (cart), oldest first. Admin. */
    @Field
    suspend fun shipments(authentication: AuthenticationContext, cartId: UUID): List<Shipment> {
        groups.verifyEcomAdmin(authentication)
        return shipmentService.getByCart(cartId)
    }

    /** A store's shipments, newest first — the fulfillment monitoring view. [status] null = all states. Admin. */
    @Field
    suspend fun shipmentsByStore(
        authentication: AuthenticationContext,
        storeId: UUID,
        status: bosca.ecommerce.model.ShipmentStatus? = null,
        offset: Int,
        limit: Int,
    ): List<Shipment> {
        groups.verifyEcomAdmin(authentication)
        return shipmentService.getByStore(storeId, status, offset, limit)
    }

    /** A single shipment by id. Admin. */
    @Field
    suspend fun shipment(authentication: AuthenticationContext, id: UUID): Shipment? {
        groups.verifyEcomAdmin(authentication)
        return shipmentService.get(id)
    }

    /** A store's returns/RMAs, newest first; [status] null = all states. Admin. */
    @Field
    suspend fun returns(
        authentication: AuthenticationContext,
        storeId: UUID,
        status: ReturnStatus? = null,
        offset: Int,
        limit: Int,
    ): List<Return> {
        groups.verifyEcomAdmin(authentication)
        return returnService.getByStore(storeId, status, offset, limit)
    }

    /** The returns against an order (cart), newest first. Admin. */
    @Field
    suspend fun returnsByCart(authentication: AuthenticationContext, cartId: UUID): List<Return> {
        groups.verifyEcomAdmin(authentication)
        return returnService.getByCart(cartId)
    }

    /** A single return by id. Admin. (`ecomReturn` — `return` is a Kotlin keyword.) */
    @Field
    suspend fun ecomReturn(authentication: AuthenticationContext, id: UUID): Return? {
        groups.verifyEcomAdmin(authentication)
        return returnService.get(id)
    }

    /** A shipping container (box type) by id. Admin. */
    @Field
    suspend fun container(authentication: AuthenticationContext, id: UUID): Container? {
        groups.verifyEcomAdmin(authentication)
        return containerService.get(id)
    }

    /** A company's shipping containers (box catalog). Admin. */
    @Field
    suspend fun containers(authentication: AuthenticationContext, companyId: UUID): List<Container> {
        groups.verifyEcomAdmin(authentication)
        return containerService.getByCompany(companyId)
    }

    @Field
    suspend fun promotion(authentication: AuthenticationContext, id: UUID): Promotion? {
        groups.verifyEcomAdmin(authentication)
        return promotionService.get(id)
    }

    /** A page of a store's promotions, newest first (operational data — admin). */
    @Field
    suspend fun promotions(authentication: AuthenticationContext, storeId: UUID, offset: Int, limit: Int): List<Promotion> {
        groups.verifyEcomAdmin(authentication)
        return promotionService.getByStore(storeId, offset, limit)
    }

    @Field
    suspend fun payment(authentication: AuthenticationContext, id: UUID): Payment? {
        groups.verifyEcomAdmin(authentication)
        return paymentService.get(id)
    }

    /** An account's payments (charges/refunds/voids), newest first. Admin. */
    @Field
    suspend fun payments(authentication: AuthenticationContext, accountId: UUID, offset: Int, limit: Int): List<Payment> {
        groups.verifyEcomAdmin(authentication)
        return paymentService.getByAccount(accountId, offset, limit)
    }

    /**
     * The audit log, newest first, with optional filters. Admin (snapshots may contain PII). The log
     * has no company column — scope by storeId and/or entityType/entityId.
     */
    @Field
    suspend fun audit(
        authentication: AuthenticationContext,
        entityType: String?,
        entityId: UUID?,
        storeId: UUID?,
        action: String?,
        offset: Int,
        limit: Int,
    ): List<Audit> {
        groups.verifyEcomAdmin(authentication)
        return auditService.list(entityType, entityId, storeId, action, offset, limit)
    }

    @Field
    suspend fun planGroup(authentication: AuthenticationContext, id: UUID): SubscriptionPlanGroup? {
        groups.verifyEcomAdmin(authentication)
        return subscriptionPlanService.getGroup(id)
    }

    /** A store's subscription plan groups. Admin. */
    @Field
    suspend fun planGroups(authentication: AuthenticationContext, storeId: UUID): List<SubscriptionPlanGroup> {
        groups.verifyEcomAdmin(authentication)
        return subscriptionPlanService.getGroupsByStore(storeId)
    }

    @Field
    suspend fun plan(authentication: AuthenticationContext, id: UUID): SubscriptionPlan? {
        groups.verifyEcomAdmin(authentication)
        return subscriptionPlanService.getPlan(id)
    }

    /** Every plan within a plan group. Admin. */
    @Field
    suspend fun plans(authentication: AuthenticationContext, planGroupId: UUID): List<SubscriptionPlan> {
        groups.verifyEcomAdmin(authentication)
        return subscriptionPlanService.getPlansByGroup(planGroupId)
    }

    @Field
    suspend fun subscription(authentication: AuthenticationContext, id: UUID): Subscription? {
        groups.verifyEcomAdmin(authentication)
        return subscriptionService.get(id)
    }

    @Field
    suspend fun subscriptions(authentication: AuthenticationContext, accountId: UUID, offset: Int, limit: Int): List<Subscription> {
        groups.verifyEcomAdmin(authentication)
        return subscriptionService.getByAccount(accountId, offset, limit)
    }

    /** Shipping rate options for a cart; owner or ecom administrator only. */
    @Field
    suspend fun shippingRates(authentication: AuthenticationContext, cartId: UUID): List<ShippingRate> {
        val cart = cartService.get(cartId) ?: error("cart $cartId not found")
        cartAccess.verify(authentication, cart)
        return shippingService.rates(cartId)
    }
}
