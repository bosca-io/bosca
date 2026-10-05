package bosca.ecommerce.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.ecommerce.events.CartCompleted
import bosca.ecommerce.events.CartCreated
import bosca.ecommerce.events.CartExpired
import bosca.ecommerce.events.CartPaid
import bosca.ecommerce.events.CartSubmitted
import bosca.ecommerce.events.dispatch
import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AddCartItemInput
import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.Currencies
import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.CartInput
import bosca.ecommerce.model.CartItem
import bosca.ecommerce.model.CartStatus
import bosca.ecommerce.model.CartStatusFlag
import bosca.ecommerce.model.CartSubmitResult
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.EmptyCartItemConfiguration
import bosca.ecommerce.model.InventoryReservation
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.RefundItemInput
import bosca.ecommerce.model.RefundTender
import bosca.ecommerce.model.ShippingCartConfiguration
import bosca.ecommerce.model.Store
import bosca.ecommerce.model.SubscriptionCartItemConfiguration
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.ShipmentStatus
import bosca.ecommerce.model.SetCartAddressInput
import bosca.ecommerce.model.SubmitPaymentInput
import bosca.ecommerce.model.TransactionType
import bosca.ecommerce.repository.CartAddressRepository
import bosca.graphql.Batch
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Carts. Item mutations run in a single [transaction] together with the inventory reservation calls:
 * because nested `transaction {}` opens a savepoint, a failed reservation rolls the whole mutation
 * back — the cart never persists a line it couldn't reserve, and partial reservations never leak.
 * Totals are recomputed on every change. Stocked products (those with inventory rows) reserve on add
 * and release on remove/expiry; un-stocked products (virtual, subscription, shipping) don't.
 */
@OptIn(ExperimentalUuidApi::class)
@ServiceImplementation
class CartServiceImpl(
    private val cartRepository: bosca.ecommerce.repository.CartRepository,
    private val cartAddressRepository: CartAddressRepository,
    private val companyService: CompanyService,
    private val storeService: StoreService,
    private val catalogService: CatalogService,
    private val catalogProductService: CatalogProductService,
    private val inventoryService: InventoryService,
    private val paymentService: PaymentService,
    private val subscriptionService: SubscriptionService,
    private val shipmentService: ShipmentService,
    private val accountService: AccountService,
    private val customerService: CustomerService,
    private val profileService: bosca.profile.profile.service.ProfileService,
    private val auditService: EcomAuditService,
) : CartService {

    // Caches the nested storefront sub-objects (company / store / account / customer) keyed by the cart id —
    // the DataLoader key. These are BATCH-ONLY caches (only ever accessed via addToBatch, which uses the
    // batchResolver), so the single-key resolver is never invoked.
    private val companyByCart: ServiceCache<UUID, Company> =
        ServiceCache("ecom:cart:company", UUIDKeySerializer, batchResolver = ::loadCompaniesForCarts) {
            error("ecom:cart:company is batch-only — use addCompaniesToBatch")
        }
    private val storeByCart: ServiceCache<UUID, Store> =
        ServiceCache("ecom:cart:store", UUIDKeySerializer, batchResolver = ::loadStoresForCarts) {
            error("ecom:cart:store is batch-only — use addStoresToBatch")
        }
    private val accountByCart: ServiceCache<UUID, Account> =
        ServiceCache("ecom:cart:account", UUIDKeySerializer, batchResolver = ::loadAccountsForCarts) {
            error("ecom:cart:account is batch-only — use addAccountsToBatch")
        }
    private val customerByCart: ServiceCache<UUID, Customer> =
        ServiceCache("ecom:cart:customer", UUIDKeySerializer, batchResolver = ::loadCustomersForCarts) {
            error("ecom:cart:customer is batch-only — use addCustomersToBatch")
        }

    override suspend fun get(id: UUID): Cart? = cartRepository.get(id)

    override suspend fun getByIds(ids: List<UUID>): List<Cart> = cartRepository.getByIds(ids)

    override suspend fun addCompaniesToBatch(batch: Batch<UUID, Company>) = companyByCart.addToBatch(batch)

    override suspend fun addStoresToBatch(batch: Batch<UUID, Store>) = storeByCart.addToBatch(batch)

    override suspend fun addAccountsToBatch(batch: Batch<UUID, Account>) = accountByCart.addToBatch(batch)

    override suspend fun addCustomersToBatch(batch: Batch<UUID, Customer>) = customerByCart.addToBatch(batch)

    /** The company cache's batch resolver: re-load the carts by id, then their companies in one batched query. */
    internal suspend fun loadCompaniesForCarts(cartIds: List<UUID>, batch: Batch<UUID, Company>) {
        val carts = cartRepository.getByIds(cartIds)
        val companies = companyService.getByIds(carts.map { it.companyId }.distinct()).associateBy { it.id }
        carts.forEach { cart -> companies[cart.companyId]?.let { batch.setData(cart.id, it) } }
    }

    /** The store cache's batch resolver: re-load the carts by id, then their stores in one batched query. */
    internal suspend fun loadStoresForCarts(cartIds: List<UUID>, batch: Batch<UUID, Store>) {
        val carts = cartRepository.getByIds(cartIds)
        val stores = storeService.getByIds(carts.map { it.storeId }.distinct()).associateBy { it.id }
        carts.forEach { cart -> stores[cart.storeId]?.let { batch.setData(cart.id, it) } }
    }

    /** The account cache's batch resolver: re-load the carts by id, then their accounts (cart.accountId may be null) in one batched query. */
    internal suspend fun loadAccountsForCarts(cartIds: List<UUID>, batch: Batch<UUID, Account>) {
        val carts = cartRepository.getByIds(cartIds)
        val accounts = accountService.getByIds(carts.mapNotNull { it.accountId }.distinct()).associateBy { it.id }
        carts.forEach { cart -> cart.accountId?.let { id -> accounts[id]?.let { batch.setData(cart.id, it) } } }
    }

    /** The customer cache's batch resolver: re-load the carts by id, then their customers (cart.customerId may be null) in one batched query. */
    internal suspend fun loadCustomersForCarts(cartIds: List<UUID>, batch: Batch<UUID, Customer>) {
        val carts = cartRepository.getByIds(cartIds)
        val customers = customerService.getByIds(carts.mapNotNull { it.customerId }.distinct()).associateBy { it.id }
        carts.forEach { cart -> cart.customerId?.let { id -> customers[id]?.let { batch.setData(cart.id, it) } } }
    }

    override suspend fun getByStore(storeId: UUID, offset: Int, limit: Int): List<Cart> =
        cartRepository.getByStore(storeId, offset, limit)

    override suspend fun getByStoreAndStatus(storeId: UUID, status: CartStatusFlag, offset: Int, limit: Int): List<Cart> =
        cartRepository.getByStoreAndStatus(storeId, status.bit, offset, limit)

    override suspend fun getOrdersByStore(storeId: UUID, offset: Int, limit: Int): List<Cart> =
        cartRepository.getPaidByStore(storeId, CartStatusFlag.PAID.bit, offset, limit)

    override suspend fun create(input: CartInput, principalId: UUID?): Cart = transaction {
        val store = storeService.get(input.storeId) ?: error("store ${input.storeId} not found")
        val catalog = catalogService.get(store.catalogId) ?: error("catalog ${store.catalogId} not found")
        val cart = cartRepository.add(
            Cart(
                companyId = store.companyId,
                storeId = store.id,
                accountId = input.accountId,
                customerId = input.customerId,
                expires = OffsetDateTime.now().plusSeconds(store.cartExpirationSeconds.toLong()),
                currency = catalog.currency,
            ),
        )
        // Pre-fill the cart's addresses from the account's saved addresses, if any (convenience so an
        // admin opening a cart for an account doesn't re-type them).
        input.accountId?.let { copyAccountAddresses(cart, it) }
        audit(cart, "created", before = null, principalId = principalId)
        CartCreated(
            cartId = cart.id, storeId = cart.storeId, companyId = cart.companyId,
            accountId = cart.accountId, customerId = cart.customerId,
        ).dispatch()
        cart
    }

    /**
     * Seeds the cart's billing/shipping addresses from the account's saved addresses (the preferred one
     * per type, else the first). The recipient name comes from the account's first customer's profile
     * (account addresses carry no name); it's left blank when the account has no customer.
     */
    private suspend fun copyAccountAddresses(cart: Cart, accountId: UUID) {
        val addresses = accountService.getAddresses(accountId)
        if (addresses.isEmpty()) return
        val name = accountService.getCustomers(accountId).firstOrNull()
            ?.let { customer -> runCatching { profileService.getById(customer.profileId).name }.getOrNull() }
            ?.trim()
            .orEmpty()
        val firstName = name.substringBefore(' ').trim()
        val lastName = name.substringAfter(' ', "").trim()
        AddressType.entries.forEach { type ->
            val byType = addresses.filter { it.type == type }
            val src = byType.firstOrNull { it.preferred } ?: byType.firstOrNull() ?: return@forEach
            cartAddressRepository.upsert(
                CartAddress(
                    cartId = cart.id, type = type, firstName = firstName, lastName = lastName,
                    address1 = src.address1, address2 = src.address2, city = src.city, state = src.state,
                    country = src.country, zip = src.zip, phone = src.phone, note = src.note,
                ),
            )
        }
    }

    override suspend fun addItem(cartId: UUID, input: AddCartItemInput, principalId: UUID?): Cart = transaction {
        val cart = openCartForUpdate(cartId)
        val catalogProduct = catalogProductService.get(input.catalogProductId)
            ?: error("catalog product ${input.catalogProductId} not found")
        require(input.quantity > 0) { "quantity must be positive" }

        // Currency-safety: a catalog product's price is denominated in ITS catalog's currency.
        // The cart is single-currency by construction (one store -> one catalog), but Money carries no
        // currency and the totals fold prices blindly — so reject a product from a differently-denominated
        // catalog here, at the composition boundary, rather than silently mixing currencies. Defense-in-depth.
        val productCatalog = catalogService.get(catalogProduct.catalogId)
            ?: error("catalog ${catalogProduct.catalogId} not found")
        check(productCatalog.currency == cart.currency) {
            "catalog product ${catalogProduct.id} is priced in ${productCatalog.currency} but cart $cartId is in ${cart.currency}"
        }

        val reservations = reserve(catalogProduct.productId, input.quantity)
        val price = catalogProduct.price
        val item = CartItem(
            id = randomId(),
            catalogProductId = catalogProduct.id,
            type = catalogProduct.type,
            quantity = input.quantity,
            baseRetailPrice = price,
            retailPrice = price,
            salesPrice = price,
            retailSubtotal = price * input.quantity,
            salesSubtotal = price * input.quantity,
            expires = cart.expires,
            parentId = input.parentId,
            configuration = input.configuration ?: EmptyCartItemConfiguration,
            reservations = reservations,
        )
        val updated = save(reprice(cart.copy(items = cart.items + item)))
        audit(updated, "item_added", before = cart, principalId = principalId)
        updated
    }

    override suspend fun updateItemQuantity(cartId: UUID, itemId: UUID, quantity: Int, principalId: UUID?): Cart =
        transaction {
            require(quantity > 0) { "quantity must be positive" }
            val cart = openCartForUpdate(cartId)
            val item = cart.items.firstOrNull { it.id == itemId } ?: error("cart item $itemId not found")
            item.reservations.forEach { inventoryService.releaseInCart(it.inventoryId, it.quantity) }
            val reservations = reserve(resolveProductId(item.catalogProductId), quantity)
            val repriced = item.copy(
                quantity = quantity,
                retailSubtotal = item.retailPrice * quantity,
                salesSubtotal = item.salesPrice * quantity,
                reservations = reservations,
            )
            val items = cart.items.map { if (it.id == itemId) repriced else it }
            val updated = save(reprice(cart.copy(items = items)))
            audit(updated, "item_updated", before = cart, principalId = principalId)
            updated
        }

    override suspend fun removeItem(cartId: UUID, itemId: UUID, principalId: UUID?): Cart = transaction {
        val cart = openCartForUpdate(cartId)
        val item = cart.items.firstOrNull { it.id == itemId } ?: error("cart item $itemId not found")
        item.reservations.forEach { inventoryService.releaseInCart(it.inventoryId, it.quantity) }
        val updated = save(reprice(cart.copy(items = cart.items.filterNot { it.id == itemId })))
        audit(updated, "item_removed", before = cart, principalId = principalId)
        updated
    }

    override suspend fun submit(cartId: UUID, payment: SubmitPaymentInput?, principalId: UUID?): CartSubmitResult = transaction {
        // Legacy parity: submit applies ONE payment of at-most the remaining due and is callable
        // repeatedly, so a cart is fulfilled by several tenders (split tender). The cart only becomes
        // PAID once payments cover the total; partial payments leave it PENDING + PAYMENT_DUE.
        val cart = payableCartForUpdate(cartId)
        check(cart.items.isNotEmpty()) { "cannot submit an empty cart" }
        // Legacy parity: a cart with any physical line cannot be submitted without a shipping
        // destination (it determines packing, shipping cost, and tax). A payment additionally needs a
        // billing address to attribute the charge. Re-checked on every submit so an address removed
        // between split-tender payments can't slip a physical order through unaddressed.
        val addresses = cartAddressRepository.getByCart(cartId)
        if (cart.items.any { it.type == ProductType.PHYSICAL }) {
            check(addresses.any { it.type == AddressType.SHIPPING }) { "a shipping address is required for physical items" }
        }
        if (payment != null) {
            check(addresses.any { it.type == AddressType.BILLING }) { "a billing address is required to take a payment" }
        }
        val due = cart.due
        val alreadyPaid = cart.status.has(CartStatusFlag.PAID)

        var status = cart.status
        if (cart.status.has(CartStatusFlag.OPEN)) {
            // First payment commits each line's holds (in_cart -> pending) and moves OPEN -> PENDING.
            cart.items.forEach { item ->
                item.reservations.forEach { inventoryService.commitToPending(it.inventoryId, it.quantity) }
            }
            status = (status - CartStatusFlag.OPEN) + CartStatusFlag.PENDING + CartStatusFlag.PAYMENT_DUE
        }

        var paid = cart.paid
        var pendingPaid = cart.pendingPaid
        var savedMethod: bosca.ecommerce.model.SavedPaymentMethod? = null
        var lastPaymentId: UUID? = null
        val payments = mutableListOf<bosca.ecommerce.model.Payment>()
        val subscriptions = mutableListOf<bosca.ecommerce.model.Subscription>()
        val subscriptionLines = cart.items.filter { it.type == ProductType.SUBSCRIPTION }

        if (payment != null) {
            // A single payment covers at most the remaining due; the balance is taken by another tender.
            // (payableCartForUpdate already guarantees the cart still owes a balance.)
            val paymentAmount = payment.amount
            val chargeAmount = if (paymentAmount != null) minOf(paymentAmount, due) else due
            // Subscription lines force vaulting so the renewal job can re-bill the saved method.
            val result = paymentService.charge(
                bosca.ecommerce.model.ChargePaymentInput(
                    storeId = cart.storeId, amount = chargeAmount, token = payment.token, type = payment.type,
                    accountId = cart.accountId, customerId = cart.customerId, cartId = cart.id,
                    save = payment.save || subscriptionLines.isNotEmpty(), email = payment.email,
                    creditCard = payment.creditCard,
                    checkNumber = payment.checkNumber, companyCreditNumber = payment.companyCreditNumber,
                    // Stable per (cart, paid-so-far, amount): a double-clicked/retried submit dedupes; a legit split-tender
                    // second payment (different paid-so-far) does not.
                    idempotencyKey = "cart:${cart.id}:${cart.paid}:$chargeAmount",
                    // Snapshot the cart's currency so the charge matches the cart's priced currency.
                    currency = cart.currency,
                ),
                principalId,
            )
            payments += result.payment
            if (result.payment.complete) {
                status -= CartStatusFlag.PAYMENT_FAILED
                // An unconfirmed tender (e.g. a check awaiting clearing) holds in pendingPaid until confirmed.
                if (result.payment.confirmed) paid += result.payment.amount else pendingPaid += result.payment.amount
                savedMethod = result.saved
                lastPaymentId = result.payment.id
            } else {
                status += CartStatusFlag.PAYMENT_FAILED
            }
        }

        // PAID once something has been paid and it covers the total (confirmed + pending); a
        // no-payment submit (pay later) stays PAYMENT_DUE, as does a partial payment.
        val covered = (paid + pendingPaid) > Money.ZERO && (paid + pendingPaid) >= cart.salesTotal
        status = if (covered) (status - CartStatusFlag.PAYMENT_DUE) + CartStatusFlag.PAID else status + CartStatusFlag.PAYMENT_DUE

        // Turn SUBSCRIPTION lines into subscriptions once, on the transition into PAID.
        if (covered && !alreadyPaid && subscriptionLines.isNotEmpty()) {
            val accountId = cart.accountId ?: error("a subscription checkout requires an account on the cart")
            subscriptionLines.forEach { line ->
                val lineConfig = line.configuration as? SubscriptionCartItemConfiguration
                    ?: error("subscription line ${line.id} has no plan selected")
                subscriptions += subscriptionService.createFromCart(cart.storeId, accountId, lineConfig.planId, cart.id, savedMethod, lastPaymentId, principalId)
            }
        }

        // On the transition into PAID, pack the order into shipments: physical lines become
        // AWAITING box-shipments and the cart moves to PREPARING. An all-digital order (nothing physical)
        // has nothing to ship, so it is immediately COMPLETE.
        if (covered && !alreadyPaid) {
            val shipments = shipmentService.createForPaidCart(cart, principalId)
            if (shipments.isNotEmpty()) {
                status += CartStatusFlag.PREPARING
            } else if (cart.items.none { it.type == ProductType.PHYSICAL }) {
                status += CartStatusFlag.COMPLETE
            }
        }

        val updated = save(reprice(cart.copy(status = status, paid = paid, pendingPaid = pendingPaid)))
        audit(updated, "submitted", before = cart, principalId = principalId)
        CartSubmitted(cartId = updated.id, storeId = updated.storeId, companyId = updated.companyId).dispatch()
        if (updated.status.has(CartStatusFlag.PAID) && !alreadyPaid) {
            CartPaid(cartId = updated.id, storeId = updated.storeId, companyId = updated.companyId).dispatch()
        }
        // An all-digital order completes at payment (physical orders complete later via advanceFulfillment).
        if (updated.status.has(CartStatusFlag.COMPLETE) && !alreadyPaid) {
            CartCompleted(cartId = updated.id, storeId = updated.storeId, companyId = updated.companyId).dispatch()
        }
        CartSubmitResult(updated, payments, subscriptions)
    }

    override suspend fun setAddress(cartId: UUID, input: SetCartAddressInput, principalId: UUID?): CartAddress = transaction {
        val cart = addressableCartForUpdate(cartId)
        val address = cartAddressRepository.upsert(
            CartAddress(
                cartId = cartId, type = input.type, firstName = input.firstName, lastName = input.lastName,
                address1 = input.address1, address2 = input.address2, city = input.city, state = input.state,
                country = input.country, zip = input.zip, phone = input.phone, email = input.email, note = input.note,
            ),
        )
        // The address determines tax jurisdiction and shipping destination, so changing it must reprice
        // the cart. Today reprice() just re-sums (no tax/shipping calculator yet); adds the
        // tax + shipping calculators that read the cart's addresses and run on this same path.
        save(reprice(cart))
        auditService.record(
            entityType = "cart_address",
            entityId = address.id,
            action = "set",
            serializer = CartAddress.serializer(),
            after = address,
            principalId = principalId,
            storeId = cart.storeId,
        )
        address
    }

    override suspend fun getAddresses(cartId: UUID): List<CartAddress> = cartAddressRepository.getByCart(cartId)

    override suspend fun setShipping(
        cartId: UUID,
        rate: bosca.ecommerce.model.ShippingRate,
        options: List<bosca.ecommerce.model.ShippingRate>,
        principalId: UUID?,
    ): Cart = transaction {
        val cart = openCartForUpdate(cartId)
        val store = storeService.get(cart.storeId) ?: error("store ${cart.storeId} not found")
        val shippingLine = CartItem(
            id = randomId(),
            catalogProductId = store.shippingCatalogProductId,
            type = ProductType.SHIPPING,
            quantity = 1,
            baseRetailPrice = rate.amount,
            retailPrice = rate.amount,
            salesPrice = rate.amount,
            retailSubtotal = rate.amount,
            salesSubtotal = rate.amount,
            expires = cart.expires,
            configuration = ShippingCartConfiguration(selected = rate, options = options),
        )
        // Replace any existing shipping line; reprice derives cart.shipping + re-runs tax over the new total.
        val items = cart.items.filterNot { it.type == ProductType.SHIPPING } + shippingLine
        val updated = save(reprice(cart.copy(items = items)))
        audit(updated, "shipping_set", before = cart, principalId = principalId)
        updated
    }

    override suspend fun expireCarts(): Int {
        var expired = 0
        // Drain the whole expired-open set in batches; expiring a cart flips it off OPEN, so it leaves
        // the candidate query and the loop terminates.
        while (true) {
            val candidates = cartRepository.getExpiredOpen(CartStatusFlag.OPEN.bit, EXPIRE_BATCH_SIZE)
            if (candidates.isEmpty()) break
            candidates.forEach { candidate -> if (expireOne(candidate.id)) expired++ }
        }
        return expired
    }

    override suspend fun advanceFulfillment(cartId: UUID, principalId: UUID?): Unit = transaction {
        val cart = cartRepository.getForUpdate(cartId) ?: return@transaction
        if (cart.status.has(CartStatusFlag.COMPLETE)) return@transaction // already terminal — idempotent
        val shipments = shipmentService.getByCart(cartId)
        if (shipments.none { it.status.isDispatched }) return@transaction // nothing has shipped yet
        // Every shipment dispatched (or cancelled) -> the order is fully shipped and complete; otherwise
        // a shipment is still awaiting dispatch, so the order is partially shipped. (Dispatched covers
        // SHIPPED and every later carrier state, so delivery progress never un-completes an order.)
        val allDone = shipments.all { it.status.isDispatched || it.status == ShipmentStatus.CANCELLED }
        val status = if (allDone) {
            (cart.status - CartStatusFlag.PREPARING - CartStatusFlag.SHIPPING) + CartStatusFlag.SHIPPED + CartStatusFlag.COMPLETE
        } else {
            (cart.status - CartStatusFlag.PREPARING) + CartStatusFlag.SHIPPING
        }
        if (status == cart.status) return@transaction // no change (already reflects this progress)
        val updated = save(cart.copy(status = status))
        audit(updated, if (allDone) "completed" else "shipping", before = cart, principalId = principalId)
        if (allDone) CartCompleted(cartId = updated.id, storeId = updated.storeId, companyId = updated.companyId).dispatch()
    }

    override suspend fun complete(cartId: UUID, principalId: UUID?): Cart = transaction {
        val cart = cartRepository.getForUpdate(cartId) ?: error("cart $cartId not found")
        check(cart.status.has(CartStatusFlag.PAID)) { "cart $cartId is not paid" }
        check(!cart.status.has(CartStatusFlag.CANCELLED)) { "cart $cartId is cancelled" }
        if (cart.status.has(CartStatusFlag.COMPLETE)) return@transaction cart // idempotent
        // Admin override (legacy setCartComplete): finalize the order — drop the in-progress flags and
        // mark it complete, emitting the same event the normal fulfillment path would.
        val status = (cart.status - CartStatusFlag.PENDING - CartStatusFlag.PREPARING - CartStatusFlag.SHIPPING) +
            CartStatusFlag.COMPLETE
        val updated = save(cart.copy(status = status))
        audit(updated, "completed", before = cart, principalId = principalId)
        CartCompleted(cartId = updated.id, storeId = updated.storeId, companyId = updated.companyId).dispatch()
        updated
    }

    override suspend fun cancel(cartId: UUID, principalId: UUID?): Cart = transaction {
        val cart = cartRepository.getForUpdate(cartId) ?: error("cart $cartId not found")
        if (cart.status.has(CartStatusFlag.CANCELLED)) return@transaction cart // idempotent
        // Admin status override (legacy setCartCancelled): a flag change only. Any money taken is
        // returned separately via refundItems / payment refunds.
        val updated = save(cart.copy(status = cart.status + CartStatusFlag.CANCELLED))
        audit(updated, "cancelled", before = cart, principalId = principalId)
        updated
    }

    override suspend fun refundItems(
        cartId: UUID,
        items: List<RefundItemInput>,
        tender: RefundTender,
        checkNumber: String?,
        reason: String?,
        principalId: UUID?,
    ): Cart = transaction {
        require(items.isNotEmpty()) { "no items to refund" }
        val cart = cartRepository.getForUpdate(cartId) ?: error("cart $cartId not found")
        check(cart.status.has(CartStatusFlag.PAID)) { "cart $cartId is not paid" }
        check(!cart.status.has(CartStatusFlag.CANCELLED)) { "cart $cartId is cancelled" }

        // Reduce each requested line by its quantity (removing it when fully refunded) and restock the
        // released units — legacy `removeItems`. The line subtotal is recomputed from the per-unit
        // price so the reprice below reflects the smaller order.
        val byId = cart.items.associateBy { it.id }
        var newItems = cart.items
        for (line in items) {
            val item = byId[line.itemId] ?: error("item ${line.itemId} not in cart $cartId")
            require(line.quantity in 1..item.quantity) {
                "refund quantity ${line.quantity} invalid for item ${item.id} (line has ${item.quantity})"
            }
            val keptReservations = releaseRefunded(item, line.quantity)
            val remaining = item.quantity - line.quantity
            newItems = if (remaining == 0) {
                newItems.filterNot { it.id == item.id }
            } else {
                newItems.map {
                    if (it.id == item.id) {
                        it.copy(
                            quantity = remaining,
                            retailSubtotal = it.retailPrice * remaining,
                            salesSubtotal = it.salesPrice * remaining,
                            reservations = keptReservations,
                        )
                    } else {
                        it
                    }
                }
            }
        }

        // Reprice: the price drop surfaces as refundDue (legacy updatePricing).
        val repriced = reprice(cart.copy(items = newItems))
        val refundAmount = repriced.refundDue
        check(refundAmount > Money.ZERO) { "no refund due after reducing the selected items" }

        // Refund refundAmount across the cart's completed charge payments (oldest first) to the chosen
        // tender, then reduce `paid` to match (legacy CartImpl.refund: data.paid += amount).
        refundAcrossPayments(cartId, refundAmount, tender, checkNumber, reason, principalId)
        val newPaid = repriced.paid - refundAmount
        // A fully-refunded order (nothing left paid) is no longer PAID; flag it REFUNDED.
        var status = repriced.status
        if (newPaid <= Money.ZERO) status = (status - CartStatusFlag.PAID) + CartStatusFlag.REFUNDED
        val settled = save(reprice(repriced.copy(paid = newPaid, status = status)))
        audit(settled, "items_refunded", before = cart, principalId = principalId)
        settled
    }

    override suspend fun confirmCheck(cartId: UUID, paymentId: UUID, checkNumber: String?, principalId: UUID?): Cart = transaction {
        val cart = cartRepository.getForUpdate(cartId) ?: error("cart $cartId not found")
        val confirmed = paymentService.confirmCheck(paymentId, checkNumber, principalId)
        require(confirmed.cartId == cartId) { "payment $paymentId is not on cart $cartId" }
        // Settle the cleared amount out of pending into paid (legacy CartImpl.confirm).
        val updated = save(
            reprice(
                cart.copy(
                    paid = cart.paid + confirmed.amount,
                    pendingPaid = (cart.pendingPaid - confirmed.amount).coerceAtLeast(Money.ZERO),
                ),
            ),
        )
        audit(updated, "check_confirmed", before = cart, principalId = principalId)
        updated
    }

    override suspend fun setLocked(cartId: UUID, locked: Boolean, principalId: UUID?): Cart = transaction {
        val cart = cartRepository.getForUpdate(cartId) ?: error("cart $cartId not found")
        val status = if (locked) cart.status + CartStatusFlag.LOCKED else cart.status - CartStatusFlag.LOCKED
        if (status == cart.status) return@transaction cart // idempotent
        val updated = save(cart.copy(status = status))
        audit(updated, if (locked) "locked" else "unlocked", before = cart, principalId = principalId)
        updated
    }

    override suspend fun setItemPrice(
        cartId: UUID,
        itemId: UUID,
        retailPrice: Money,
        salesPrice: Money,
        principalId: UUID?,
    ): Cart = transaction {
        val cart = cartRepository.getForUpdate(cartId) ?: error("cart $cartId not found")
        val item = cart.items.firstOrNull { it.id == itemId } ?: error("cart item $itemId not found")
        // Override the unit prices and rebuild the line subtotals from them; reprice folds the change
        // into the totals (and any resulting due/refundDue). Legacy `setCartItemPrice`.
        val priced = item.copy(
            retailPrice = retailPrice,
            salesPrice = salesPrice,
            retailSubtotal = retailPrice * item.quantity,
            salesSubtotal = salesPrice * item.quantity,
        )
        val items = cart.items.map { if (it.id == itemId) priced else it }
        val updated = save(reprice(cart.copy(items = items)))
        audit(updated, "item_price_set", before = cart, principalId = principalId)
        updated
    }

    override suspend fun removeAddress(cartId: UUID, type: AddressType, principalId: UUID?): Cart = transaction {
        val cart = cartRepository.getForUpdate(cartId) ?: error("cart $cartId not found")
        cartAddressRepository.deleteByCartAndType(cartId, type)
        // Reprice (an address change can move tax/shipping) and persist; audit the cart.
        val updated = save(reprice(cart))
        audit(updated, "address_removed", before = cart, principalId = principalId)
        updated
    }

    override suspend fun setBillingSameAsShipping(cartId: UUID, value: Boolean, principalId: UUID?): Cart = transaction {
        val cart = cartRepository.getForUpdate(cartId) ?: error("cart $cartId not found")
        // When billing mirrors shipping, drop any separate billing address (legacy setBillingSameAsShipping).
        if (value) cartAddressRepository.deleteByCartAndType(cartId, AddressType.BILLING)
        val updated = save(reprice(cart.copy(billingSameAsShipping = value)))
        audit(updated, "billing_same_as_shipping_set", before = cart, principalId = principalId)
        updated
    }

    /**
     * Restocks [quantity] units of a paid line by releasing them from pending inventory (a paid cart's
     * holds were committed to pending at submit) and returns the line's remaining reservations. Lines
     * with no reservations (virtual/subscription/shipping) restock nothing.
     */
    private suspend fun releaseRefunded(item: CartItem, quantity: Int): List<InventoryReservation> {
        var remaining = quantity
        val kept = mutableListOf<InventoryReservation>()
        for (reservation in item.reservations) {
            if (remaining <= 0) {
                kept += reservation
                continue
            }
            val release = minOf(remaining, reservation.quantity)
            if (release > 0) inventoryService.releasePending(reservation.inventoryId, release)
            remaining -= release
            val left = reservation.quantity - release
            if (left > 0) kept += reservation.copy(quantity = left)
        }
        return kept
    }

    /** Refunds [amount] across the cart's completed charge payments (oldest first) to [tender]. */
    private suspend fun refundAcrossPayments(
        cartId: UUID,
        amount: Money,
        tender: RefundTender,
        checkNumber: String?,
        reason: String?,
        principalId: UUID?,
    ) {
        var remaining = amount
        // getAllByCart (not the paged getByCart): refund math must see every tender, never a page.
        val payments = paymentService.getAllByCart(cartId)
            // `confirmed` excludes a complete-but-uncleared tender (e.g. a check awaiting clearing) — never
            // refund against money that hasn't actually been collected.
            .filter { it.transactionType == TransactionType.PAYMENT && it.complete && it.confirmed && it.voided == null }
        for (payment in payments) {
            if (remaining <= Money.ZERO) break
            val refundable = payment.amount - payment.refundedAmount - payment.nonRefundableAmount
            if (refundable <= Money.ZERO) continue
            val take = minOf(remaining, refundable)
            when (tender) {
                RefundTender.ORIGINAL -> paymentService.refund(payment.id, take, reason, principalId)
                RefundTender.ACCOUNT_CREDIT -> paymentService.refundToAccountCredit(payment.id, take, reason, principalId)
                RefundTender.CHECK -> paymentService.refundToCheck(payment.id, take, checkNumber, reason, principalId)
            }
            remaining -= take
        }
        check(remaining <= Money.ZERO) {
            "could not refund $amount: only ${amount - remaining} was refundable across the cart's payments"
        }
    }

    /** Re-locks the candidate and reclaims it iff it is still an expired OPEN cart. */
    private suspend fun expireOne(cartId: UUID): Boolean = transaction {
        val cart = cartRepository.getForUpdate(cartId) ?: return@transaction false
        if (!cart.status.has(CartStatusFlag.OPEN) || cart.expires.isAfter(OffsetDateTime.now())) {
            return@transaction false
        }
        cart.items.forEach { item ->
            item.reservations.forEach { inventoryService.releaseInCart(it.inventoryId, it.quantity) }
        }
        val status = (cart.status - CartStatusFlag.OPEN - CartStatusFlag.PENDING - CartStatusFlag.PREPARING) +
            CartStatusFlag.CANCELLED + CartStatusFlag.EXPIRED
        val updated = save(reprice(cart.copy(items = emptyList(), status = status)))
        audit(updated, "expired", before = cart, principalId = null)
        CartExpired(cartId = updated.id, storeId = updated.storeId, companyId = updated.companyId).dispatch()
        true
    }

    /** Loads a cart for update, requiring it to be OPEN. */
    private suspend fun openCartForUpdate(cartId: UUID): Cart {
        val cart = cartRepository.getForUpdate(cartId) ?: error("cart $cartId not found")
        check(cart.status.has(CartStatusFlag.OPEN)) { "cart $cartId is not open" }
        return cart
    }

    /** A cart that can still take a payment: OPEN (first payment) or PENDING (a further split payment), not yet fully paid. */
    private suspend fun payableCartForUpdate(cartId: UUID): Cart {
        val cart = cartRepository.getForUpdate(cartId) ?: error("cart $cartId not found")
        check(cart.status.has(CartStatusFlag.OPEN) || cart.status.has(CartStatusFlag.PENDING)) { "cart $cartId is not payable" }
        check(!cart.status.has(CartStatusFlag.PAID)) { "cart $cartId is already paid" }
        check(!cart.status.has(CartStatusFlag.CANCELLED) && !cart.status.has(CartStatusFlag.EXPIRED)) { "cart $cartId is closed" }
        return cart
    }

    /**
     * A cart whose addresses can still be edited: OPEN (being assembled) or PENDING but not yet PAID,
     * and not closed. Addresses are set during assembly or corrected between split-tender payments —
     * the same window as [payableCartForUpdate] — so a committed-but-unpaid cart can still gain the
     * shipping address its physical lines require.
     */
    private suspend fun addressableCartForUpdate(cartId: UUID): Cart {
        val cart = cartRepository.getForUpdate(cartId) ?: error("cart $cartId not found")
        check((cart.status.has(CartStatusFlag.OPEN) || cart.status.has(CartStatusFlag.PENDING)) && !cart.status.has(CartStatusFlag.PAID)) {
            "cart $cartId addresses can only be edited before it is paid"
        }
        check(!cart.status.has(CartStatusFlag.CANCELLED) && !cart.status.has(CartStatusFlag.EXPIRED)) { "cart $cartId is closed" }
        return cart
    }

    /**
     * Reserves [quantity] units of the product across its inventory rows (greedy by availability).
     * Each [InventoryService.reserve] re-checks availability under a row lock, so an oversell rolls
     * the whole cart mutation back. Products with no inventory rows (virtual/subscription/shipping)
     * reserve nothing.
     */
    private suspend fun reserve(productId: UUID, quantity: Int): List<InventoryReservation> {
        val inventories = inventoryService.getByProduct(productId)
        if (inventories.isEmpty()) return emptyList()
        var remaining = quantity
        val reservations = mutableListOf<InventoryReservation>()
        for (inventory in inventories) {
            if (remaining <= 0) break
            val take = minOf(remaining, inventory.available)
            if (take <= 0) continue
            inventoryService.reserve(inventory.id, take)
            reservations += InventoryReservation(inventory.id, take)
            remaining -= take
        }
        check(remaining <= 0) { "insufficient inventory for product $productId: short by $remaining" }
        return reservations
    }

    /** Resolves a catalog product's backing product id (for re-reservation on quantity change). */
    private suspend fun resolveProductId(catalogProductId: UUID): UUID {
        val catalogProduct = catalogProductService.get(catalogProductId)
            ?: error("catalog product $catalogProductId not found")
        return catalogProduct.productId
    }

    private suspend fun save(cart: Cart): Cart =
        cartRepository.update(cart) ?: error("cart ${cart.id} not found")

    override suspend fun reprice(cartId: UUID): Cart = transaction {
        val cart = cartRepository.getForUpdate(cartId) ?: error("cart $cartId not found")
        save(reprice(cart))
    }

    /**
     * The single re-pricing funnel every cart mutation runs through (add/update/remove item,
     * setAddress, submit, expire). Runs every registered [CartPricer] in order — promotions,
     * then tax + shipping — which adjust line prices/discounts/taxes, then recomputes totals.
     * Pricers are idempotent, so routing every mutation through here means an address change reprices
     * exactly like an item change.
     */
    @OptIn(InternalDI::class)
    private suspend fun reprice(cart: Cart): Cart {
        var priced = cart
        ProviderRegistry.findAll(CartPricer::class)
            .map { it.get() }
            .sortedBy { it.order }
            .forEach { pricer -> priced = pricer.price(priced) }
        return priced.recomputed(priced.items)
    }

    /** Recomputes denormalized totals from the line items (pricers set discounts/taxes; shipping is a line). */
    private fun Cart.recomputed(items: List<CartItem>): Cart {
        val retailSubtotal = items.fold(Money.ZERO) { acc, item -> acc + item.retailSubtotal }
        val salesSubtotal = items.fold(Money.ZERO) { acc, item -> acc + item.salesSubtotal }
        val discounts = items.fold(Money.ZERO) { acc, item -> acc + item.discounts }
        val tax = items.fold(Money.ZERO) { acc, item -> acc + item.taxes.taxes }
        // Shipping is itself a SHIPPING line, so it's already inside salesSubtotal — `shipping` is the
        // denormalized view of it, NOT added again into the totals.
        val shipping = items.filter { it.type == ProductType.SHIPPING }.fold(Money.ZERO) { acc, item -> acc + item.salesSubtotal }
        val quantity = items.sumOf { it.quantity }
        // Quantize the money-of-record to the currency's settlement precision (2 most, 0 for JPY, …) so the
        // charge target and coverage comparison are against an amount a gateway can actually move — a
        // sub-cent tax residual must not leave a fully-paid cart perpetually short of PAID.
        val scale = Currencies.settlementScale(currency)
        val salesTotal = (salesSubtotal - discounts + tax).quantize(scale)
        val retailTotal = (retailSubtotal + tax).quantize(scale)
        // Split the balance into the two directions, mirroring the legacy `updatePricing`: `due` is
        // what the buyer still owes (never below zero); `refundDue` is what we owe back when payments
        // exceed the (now lower) sales total. Reducing/removing paid lines drops salesTotal and
        // surfaces a refundDue, and the REFUND_DUE flag is derived from it (legacy CartImpl:920-926).
        val balance = salesTotal - paid - pendingPaid
        val refundDue = (-balance).coerceAtLeast(Money.ZERO).quantize(scale)
        val status = if (refundDue > Money.ZERO) status + CartStatusFlag.REFUND_DUE else status - CartStatusFlag.REFUND_DUE
        return copy(
            items = items,
            status = status,
            retailSubtotal = retailSubtotal,
            salesSubtotal = salesSubtotal,
            discounts = discounts,
            tax = tax,
            shipping = shipping,
            salesTotal = salesTotal,
            retailTotal = retailTotal,
            due = balance.coerceAtLeast(Money.ZERO).quantize(scale),
            refundDue = refundDue,
            quantity = quantity,
        )
    }

    private suspend fun audit(cart: Cart, action: String, before: Cart?, principalId: UUID?) {
        auditService.record(
            entityType = "cart",
            entityId = cart.id,
            action = action,
            serializer = Cart.serializer(),
            before = before,
            after = cart,
            principalId = principalId,
            storeId = cart.storeId,
        )
    }

    private fun randomId(): UUID = Uuid.random()

    private companion object {
        const val EXPIRE_BATCH_SIZE = 500
    }
}
