package bosca.security.model

import kotlinx.serialization.Serializable

/**
 * The ways a user can prove ownership of an existing account before a new sign-in method is linked
 * to it (see the account-linking / pending-link flow).
 *
 * - [PASSWORD]: re-authenticate by entering the existing account's password. Only offered when the
 *   account actually has a password credential.
 * - [EMAIL]: prove control of the account's verified email by clicking a one-time emailed link. Always
 *   available, and the only option for accounts that have no password (e.g. OAuth-only accounts).
 */
@Serializable
enum class LinkProofMethod {
    PASSWORD,
    EMAIL,
}
