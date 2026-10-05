package bosca.ecommerce.graphql

import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/**
 * The dedicated ecommerce administrators group. Entities that do NOT carry their own attached
 * permissions (companies, catalogs, manufacturers, accounts, stores, providers, promotions, plans —
 * everything except the content-Metadata-backed Product) are gated on this group. Metadata-backed
 * products are instead gated on the backing document's permissions
 * (`MetadataPermissionEvaluator.verifyAllowed`). layers the full per-company permission model
 * on top of this baseline.
 */
const val ECOM_ADMINISTRATOR_GROUP: String = "ecom.administrator"

/** Verify the caller belongs to the ecommerce administrators group; throws otherwise. */
fun GroupEvaluator.verifyEcomAdmin(authentication: AuthenticationContext?) =
    verifyHasGroup(authentication, ECOM_ADMINISTRATOR_GROUP)
