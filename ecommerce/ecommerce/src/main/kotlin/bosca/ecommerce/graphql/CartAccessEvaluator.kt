package bosca.ecommerce.graphql

import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartInput
import bosca.ecommerce.service.AccountService
import bosca.ecommerce.service.CustomerService
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/**
 * Authorizes cart access. A cart belongs to its [Cart.customerId] (resolved customer -> profile); the
 * caller may operate on it only if their principal -> profile matches that owner, or if they are an
 * ecommerce administrator. Carts with no customer (e.g. admin-created) are admin-only. (Anonymous
 * guest carts gated by a cart token are a follow-up.)
 */
class CartAccessEvaluator(
    private val profileService: ProfileService,
    private val customerService: CustomerService,
    private val accountService: AccountService,
    private val groups: GroupEvaluator,
) {

    /** Authorize access to an existing cart; throws if neither owner nor admin. */
    suspend fun verify(authentication: AuthenticationContext?, cart: Cart) {
        val customer = cart.customerId?.let { customerService.get(it) }
        if (isOwner(authentication, customer?.profileId)) {
            verifyAccountTenancy(cart.accountId, customer?.companyId)
            return
        }
        groups.verifyEcomAdmin(authentication)
    }

    /** Authorize creating a cart for the given owner; a caller may only create their own cart (or admin). */
    suspend fun verifyCreate(authentication: AuthenticationContext?, input: CartInput) {
        val customer = input.customerId?.let { customerService.get(it) }
        if (isOwner(authentication, customer?.profileId)) {
            verifyAccountTenancy(input.accountId, customer?.companyId)
            return
        }
        groups.verifyEcomAdmin(authentication)
    }

    /**
     * A buyer-supplied [accountId] must belong to the buyer's own company ([ownerCompanyId]). Account ids
     * are not secrets, so without this an owner could attach another company's account to their cart and
     * spend its stored ACCOUNT_CREDIT balance at submit (cross-tenant theft). Admins take the
     * [GroupEvaluator.verifyEcomAdmin] path and bypass this owner-scoped check; reached only on the owner
     * path, where [ownerCompanyId] is non-null.
     */
    private suspend fun verifyAccountTenancy(accountId: UUID?, ownerCompanyId: UUID?) {
        if (accountId == null) return
        val account = accountService.get(accountId) ?: error("account $accountId not found")
        check(account.companyId == ownerCompanyId) {
            "account $accountId does not belong to this cart's company"
        }
    }

    private suspend fun isOwner(authentication: AuthenticationContext?, ownerProfileId: UUID?): Boolean {
        if (ownerProfileId == null) return false
        val principal = authentication?.principal()?.asPrincipal() ?: return false
        val callerProfileId = profileService.getPrimaryProfile(principal)?.id
        return callerProfileId != null && callerProfileId == ownerProfileId
    }
}
