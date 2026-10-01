package bosca.security.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** A labeled detail rendered by a security-alert email pipeline. */
@Serializable
data class SecurityEmailDetail(
    val label: String,
    val value: String,
)

/** Base contract for security email intents consumed by triggered pipelines. */
interface SecurityEmailEvent : Event {
    val recipientIds: Set<UUID>
}

/** Requests the account-created welcome email after the account's primary profile exists. */
@JobEvent(jobs = [], pubsubChannel = "bosca.security.email.welcome_requested")
@Serializable
data class WelcomeEmailRequested(
    override val recipientIds: Set<@Contextual UUID>,
    val getStartedUrl: String,
) : SecurityEmailEvent

/** Requests an email-verification challenge containing its already-validated public link. */
@JobEvent(jobs = [], pubsubChannel = "bosca.security.email.verification_requested")
@Serializable
data class EmailVerificationRequested(
    override val recipientIds: Set<@Contextual UUID>,
    val verifyUrl: String,
    val expiresIn: String = "",
) : SecurityEmailEvent

/** Requests an account-recovery email after a reset token has been persisted. */
@JobEvent(jobs = [], pubsubChannel = "bosca.security.email.password_reset_requested")
@Serializable
data class PasswordResetEmailRequested(
    override val recipientIds: Set<@Contextual UUID>,
    val resetUrl: String,
    val expiresIn: String = "",
) : SecurityEmailEvent

/** Requests proof that a new sign-in method may be linked to an existing account. */
@JobEvent(jobs = [], pubsubChannel = "bosca.security.email.account_link_requested")
@Serializable
data class AccountLinkEmailRequested(
    override val recipientIds: Set<@Contextual UUID>,
    val confirmUrl: String,
) : SecurityEmailEvent

/** Requests an always-on security alert for a notable account event. */
@JobEvent(jobs = [], pubsubChannel = "bosca.security.email.alert_requested")
@Serializable
data class SecurityAlertEmailRequested(
    override val recipientIds: Set<@Contextual UUID>,
    val event: String,
    val time: String,
    val details: List<SecurityEmailDetail> = emptyList(),
    val reviewUrl: String,
) : SecurityEmailEvent
