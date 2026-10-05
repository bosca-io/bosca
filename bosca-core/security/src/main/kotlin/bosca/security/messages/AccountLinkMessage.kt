package bosca.security.messages

import bosca.communications.service.MessageService
import bosca.serialization.UUID

/** Email template for the account-link confirmation magic link (`security.link.requestEmailProof`). */
interface AccountLinkTemplate : EmailTemplate

/**
 * The "confirm a new sign-in method" email, sent to a target account's verified address when someone
 * tries to link a new credential to it. Uses the shared [AbstractMessage] envelope (channels, recipients,
 * HTML+TEXT content) so it stays consistent with the verification / forgot-password emails.
 */
class AccountLinkMessage(
    messages: MessageService,
    templates: AccountLinkTemplate,
) : AbstractMessage<AccountLinkTemplate>(messages, templates)

/**
 * Default account-link template. The confirm URL is per-request (it carries the one-time email-proof
 * token), so it is supplied at construction rather than derived from the profile in [initialize].
 */
class DefaultAccountLinkTemplate(private val confirmUrl: String) : AccountLinkTemplate {

    override suspend fun initialize(profileId: UUID) = Unit

    override suspend fun getSubject() = "Confirm a new sign-in method for your account"

    override suspend fun getHtml() =
        "<p>Someone is trying to connect a new sign-in method to your account. " +
            "If this was you, confirm by clicking <a href=\"$confirmUrl\">this link</a>. " +
            "If it wasn't you, you can safely ignore this email.</p>"

    override suspend fun getText() =
        "Someone is trying to connect a new sign-in method to your account. " +
            "If this was you, confirm here: $confirmUrl\nIf it wasn't you, you can safely ignore this email."
}
