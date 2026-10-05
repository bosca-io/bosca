package bosca.feeds.graphql

import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/**
 * The dedicated feeds administrators group. Bosca-managed (`MANAGED`) feed-source mutations and the
 * admin read surface gate on this group. User-owned sources will gate on ownership instead.
 */
const val FEEDS_ADMINISTRATOR_GROUP: String = "feeds.administrator"

/** Verify the caller belongs to the feeds administrators group; throws otherwise. */
fun GroupEvaluator.verifyFeedsAdmin(authentication: AuthenticationContext?) =
    verifyHasGroup(authentication, FEEDS_ADMINISTRATOR_GROUP)
