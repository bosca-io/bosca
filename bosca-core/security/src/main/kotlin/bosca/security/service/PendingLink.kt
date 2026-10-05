package bosca.security.service

import bosca.security.model.CredentialType
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The server-held intent captured when a sign-up collides with an existing verified account: the new
 * sign-in method the user wants to add, plus the account it must be linked to once ownership is proven.
 *
 * Held only in the distributed cache under a single-use, short-lived token — never persisted — so an
 * unconfirmed link leaves no durable state. The [credentialAttributes] are stored in their FINAL form
 * (passwords already encoded) so confirmation attaches them verbatim without re-encoding.
 */
@Serializable
data class PendingLink(
    @Contextual val targetPrincipalId: UUID,
    val email: String,
    val credentialType: CredentialType,
    val credentialAttributes: JsonElement,
    /**
     * Set ONLY for a verify-time collision, where the credential being linked already lives on a separate,
     * just-proven DUPLICATE principal (rather than being a brand-new sign-up method). On confirmation the
     * duplicate is retired and its profiles discarded BEFORE the credential is attached to the survivor, so
     * the credential's globally-unique identifier frees up. Null for the ordinary sign-up link flow, where no
     * duplicate principal was ever created. Defaults to null so existing cached links deserialize unchanged.
     */
    @Contextual val retirePrincipalId: UUID? = null,
    /**
     * The target profile the email-proof magic-link is delivered to, resolved and pinned WHEN the collision
     * is detected (the survivor and the matched address are both in hand then) rather than re-resolved at
     * send time. Pinning here guarantees the proof can only ever go to the address that was matched — a later
     * email change can't redirect it — and lets the send path stay a simple lookup. Null when no profile of
     * the target carries the matched address (nothing safe to deliver to); defaults to null so existing
     * cached links deserialize unchanged.
     */
    @Contextual val deliveryProfileId: UUID? = null,
)
