package bosca.security.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.security.model.CredentialType
import bosca.security.model.GroupType
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/** Base for all principal-scoped security events. */
interface PrincipalEvent : Event {

    val principalId: UUID
}

@JobEvent(
    jobs = [],
    displayName = "Account Created",
    description = "Fires when a new principal (account) is created, including OAuth sign-ups. " +
        "`verified` is true when the account was created with a provider-verified email."
)
@Serializable
class PrincipalCreated(override val principalId: UUID, val verified: Boolean) : PrincipalEvent

@JobEvent(
    jobs = [],
    displayName = "Account Updated",
    description = "Fires when a principal row is edited. Includes internal edits made during " +
        "verification, merges, primary-profile changes, and password-reset token stamping."
)
@Serializable
class PrincipalUpdated(override val principalId: UUID) : PrincipalEvent {

    override fun identityKey(): Any = principalId
}

@JobEvent(
    jobs = [],
    displayName = "Account Marked Deleted",
    description = "Fires when a principal is soft-deleted (marked for deletion); its sessions are revoked."
)
@Serializable
class PrincipalMarkedDeleted(override val principalId: UUID) : PrincipalEvent

@JobEvent(
    jobs = [],
    displayName = "Account Restored",
    description = "Fires when a soft-deleted principal is restored."
)
@Serializable
class PrincipalRestored(override val principalId: UUID) : PrincipalEvent

@JobEvent(
    jobs = [],
    displayName = "Account Deleted",
    description = "Fires when a principal is permanently deleted along with its profiles."
)
@Serializable
class PrincipalDeleted(override val principalId: UUID) : PrincipalEvent

@JobEvent(
    jobs = [],
    displayName = "Accounts Merged",
    description = "Fires when a duplicate principal is merged into a survivor. The duplicate is retired; " +
        "its credentials, profiles, and group memberships move to the survivor (each move also fires its own event)."
)
@Serializable
class PrincipalsMerged(val survivorId: UUID, val duplicateId: UUID) : Event

@JobEvent(
    jobs = [],
    displayName = "Email Verified",
    description = "Fires when a principal proves control of its email via a verification link. " +
        "Accounts created with a provider-verified email fire Account Created with verified=true instead."
)
@Serializable
class EmailVerified(override val principalId: UUID) : PrincipalEvent

@JobEvent(
    jobs = [],
    displayName = "Signed In",
    description = "Fires on every successful interactive sign-in. `method` is one of: password, third_party, " +
        "refresh_token, jwt, exchange_token, passkey. Per-request basic authentication does not fire this event."
)
@Serializable
class PrincipalSignedIn(override val principalId: UUID, val method: String) : PrincipalEvent

@JobEvent(
    jobs = [],
    displayName = "Logins Revoked",
    description = "Fires when one or all of a principal's logins are revoked. `loginId` identifies a targeted " +
        "login; null means every access and refresh token was revoked for a legacy login."
)
@Serializable
class PrincipalLoginsRevoked(
    override val principalId: UUID,
    val loginId: Long? = null,
) : PrincipalEvent

@JobEvent(
    jobs = [],
    displayName = "Password Changed",
    description = "Fires whenever a principal's password is set or changed, including during a password reset."
)
@Serializable
class PasswordChanged(override val principalId: UUID) : PrincipalEvent

@JobEvent(
    jobs = [],
    displayName = "Password Reset Requested",
    description = "Fires when a forgot-password request matches a verified account and a reset email is sent."
)
@Serializable
class PasswordResetRequested(override val principalId: UUID) : PrincipalEvent

@JobEvent(
    jobs = [],
    displayName = "Password Reset",
    description = "Fires when a password reset token is redeemed and a new password is set. " +
        "Password Changed also fires for the same operation."
)
@Serializable
class PasswordReset(override val principalId: UUID) : PrincipalEvent

@JobEvent(
    jobs = [],
    displayName = "Credential Linked",
    description = "Fires when a new sign-in method (password, OAuth identity, API token) is attached to a principal. " +
        "Re-attaching an already-owned credential does not fire. Passkeys fire Passkey Added instead."
)
@Serializable
class CredentialLinked(override val principalId: UUID, val credentialType: CredentialType) : PrincipalEvent

@JobEvent(
    jobs = [],
    displayName = "Credential Deleted",
    description = "Fires when a sign-in method is removed from a principal."
)
@Serializable
class CredentialDeleted(override val principalId: UUID, val credentialType: CredentialType) : PrincipalEvent

@JobEvent(
    jobs = [],
    displayName = "Passkey Added",
    description = "Fires when a passkey (WebAuthn credential) is registered for a principal."
)
@Serializable
class PasskeyAdded(override val principalId: UUID) : PrincipalEvent

@JobEvent(
    jobs = [],
    displayName = "Group Created",
    description = "Fires when a security group is created. Sign-ups create a personal group with type PRINCIPAL; " +
        "filter on type SYSTEM for admin-managed groups."
)
@Serializable
class GroupCreated(val groupId: UUID, val name: String, val type: GroupType) : Event

@JobEvent(
    jobs = [],
    displayName = "Group Updated",
    description = "Fires when a security group is edited."
)
@Serializable
class GroupUpdated(val groupId: UUID, val name: String, val type: GroupType) : Event {

    override fun identityKey(): Any = groupId
}

@JobEvent(
    jobs = [],
    displayName = "Group Deleted",
    description = "Fires when a security group is deleted."
)
@Serializable
class GroupDeleted(val groupId: UUID) : Event

@JobEvent(
    jobs = [],
    displayName = "Added To Group",
    description = "Fires when a principal is added to a security group. Also fires during sign-up " +
        "(personal group) and account merges."
)
@Serializable
class PrincipalAddedToGroup(override val principalId: UUID, val groupId: UUID) : PrincipalEvent

@JobEvent(
    jobs = [],
    displayName = "Removed From Group",
    description = "Fires when a principal is removed from a security group."
)
@Serializable
class PrincipalRemovedFromGroup(override val principalId: UUID, val groupId: UUID) : PrincipalEvent
