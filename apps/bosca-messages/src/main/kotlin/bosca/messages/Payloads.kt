package bosca.messages

import kotlinx.serialization.Serializable

/** A labeled value row — submitted form fields, security-event details (device, location, IP). */
@Serializable
data class LabeledValue(
    val label: String,
    val value: String,
)

/** One "what you can do next" row on the welcome email. */
@Serializable
data class Highlight(
    val title: String,
    val description: String,
)

/** `welcome` — sent when an account is created. */
@Serializable
data class Welcome(
    val appName: String = "Bosca",
    /** Absolute URL of a masthead logo overriding the default Bosca mark; blank keeps the default. */
    val logoUrl: String = "",
    /** Hides the masthead title so the logo is the complete brand treatment. */
    val logoOnly: Boolean = false,
    /** Main text and action color, supplied from `bosca.messages.branding` in production. */
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    /** Highlight color, supplied from `bosca.messages.branding` in production. */
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    /** Inbox preheader: pairs with the "Welcome to {app}" subject. */
    val preheader: String = "Your account is ready.",
    /** Smart link: wherever getting started actually begins for this user. */
    val getStartedUrl: String = "#",
    /** Optional "what you can do" rows; the section hides when empty. */
    val highlights: List<Highlight> = emptyList(),
)

/** `verify-email` — the email-verification challenge. */
@Serializable
data class VerifyEmail(
    val appName: String = "Bosca",
    /** Absolute URL of a masthead logo overriding the default Bosca mark; blank keeps the default. */
    val logoUrl: String = "",
    /** Hides the masthead title so the logo is the complete brand treatment. */
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    val verifyUrl: String,
    /** Human wording, e.g. "24 hours"; blank hides the expiry line. */
    val expiresIn: String = "",
)

/** `reset-password` — account recovery after a forgot-password request. */
@Serializable
data class ResetPassword(
    val appName: String = "Bosca",
    /** Absolute URL of a masthead logo overriding the default Bosca mark; blank keeps the default. */
    val logoUrl: String = "",
    /** Hides the masthead title so the logo is the complete brand treatment. */
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    val resetUrl: String,
    /** Human wording, e.g. "1 hour"; blank hides the expiry line. */
    val expiresIn: String = "",
)

/** `security-alert` — a notable security event on the account. */
@Serializable
data class SecurityAlert(
    val appName: String = "Bosca",
    /** Absolute URL of a masthead logo overriding the default Bosca mark; blank keeps the default. */
    val logoUrl: String = "",
    /** Hides the masthead title so the logo is the complete brand treatment. */
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    /** The event headline, e.g. "New sign-in to your account" / "Your password was changed". */
    val event: String,
    /** Display time of the event (already formatted for the recipient). */
    val time: String,
    /** Optional context rows: device, browser, location, IP address. */
    val details: List<LabeledValue> = emptyList(),
    /** Where to review activity / secure the account. */
    val reviewUrl: String = "#",
)

/** `account-link` — confirms linking a new sign-in method to an existing account. */
@Serializable
data class AccountLink(
    val appName: String = "Bosca",
    /** Absolute URL of a masthead logo overriding the default Bosca mark; blank keeps the default. */
    val logoUrl: String = "",
    /** Hides the masthead title so the logo is the complete brand treatment. */
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    val confirmUrl: String,
)

/** `form-submission` — reviewer notification when a form submission is created. */
@Serializable
data class FormSubmission(
    val appName: String = "Bosca",
    /** Absolute URL of a masthead logo overriding the default Bosca mark; blank keeps the default. */
    val logoUrl: String = "",
    /** Hides the masthead title so the logo is the complete brand treatment. */
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    val formName: String,
    /** Optional form-configured subject; blank or absent uses the localized default. */
    val subject: String? = null,
    /** Null when the submitter is unknown — the email shows "Anonymous". */
    val submitterName: String? = null,
    val submitterEmail: String? = null,
    /** Display time of the submission (already formatted for the recipient). */
    val submittedAt: String,
    /** The submitted fields, flattened by the send path into label/value rows. */
    val fields: List<LabeledValue> = emptyList(),
    /** Deep link to the submission in Studio. */
    val reviewUrl: String = "#",
)

/** `form-submission-receipt` — confirmation to the submitter. */
@Serializable
data class FormSubmissionReceipt(
    val appName: String = "Bosca",
    /** Absolute URL of a masthead logo overriding the default Bosca mark; blank keeps the default. */
    val logoUrl: String = "",
    /** Hides the masthead title so the logo is the complete brand treatment. */
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    val formName: String,
    /** Display time of the submission (already formatted for the recipient). */
    val submittedAt: String,
    /** Optional echo of what was submitted; the section hides when empty. */
    val fields: List<LabeledValue> = emptyList(),
    /** Optional "what happens next" line supplied by the form's configuration. */
    val nextSteps: String = "",
)

/** `git-pull-request` — one activity notification for the full pull-request lifecycle. */
@Serializable
data class GitPullRequestNotification(
    val appName: String = "Bosca",
    /** Absolute URL of a masthead logo overriding the default Bosca mark; blank keeps the default. */
    val logoUrl: String = "",
    /** Hides the masthead title so the logo is the complete brand treatment. */
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    val repositoryName: String,
    val number: Int,
    val title: String,
    /** One of the `PullRequestEventAction` names emitted by core-git. */
    val action: String,
    val actorName: String? = null,
    val sourceBranch: String,
    val targetBranch: String,
    val body: String? = null,
    val filePath: String? = null,
    val lineNumber: Int? = null,
    val reviewStatus: String? = null,
    val taskKeys: List<String> = emptyList(),
    val pullRequestUrl: String,
)

/** `git-ref-update` — branch/tag creation, advancement, and deletion activity. */
@Serializable
data class GitRefUpdateNotification(
    val appName: String = "Bosca",
    /** Absolute URL of a masthead logo overriding the default Bosca mark; blank keeps the default. */
    val logoUrl: String = "",
    /** Hides the masthead title so the logo is the complete brand treatment. */
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    val repositoryName: String,
    val refName: String,
    /** `BRANCH` or `TAG`. */
    val kind: String,
    /** `CREATED`, `UPDATED`, or `DELETED`. */
    val action: String,
    val beforeSha: String? = null,
    val afterSha: String? = null,
    val taskKeys: List<String> = emptyList(),
    val commitMessages: List<String> = emptyList(),
    val repositoryUrl: String,
)

/** `workops-notification` — task, specification, requirement, and comment activity. */
@Serializable
data class WorkOpsNotification(
    val appName: String = "Bosca",
    /** Absolute URL of a masthead logo overriding the default Bosca mark; blank keeps the default. */
    val logoUrl: String = "",
    /** Hides the masthead title so the logo is the complete brand treatment. */
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    val event: String,
    val projectName: String,
    val entityType: String,
    val entityKey: String,
    val title: String,
    val body: String,
    val actorName: String? = null,
    val actionUrl: String,
)

/** `workops-automation` — an email body authored by a WorkOps automation rule. */
@Serializable
data class WorkOpsAutomationEmail(
    val appName: String = "Bosca",
    /** Absolute URL of a masthead logo overriding the default Bosca mark; blank keeps the default. */
    val logoUrl: String = "",
    /** Hides the masthead title so the logo is the complete brand treatment. */
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    val subject: String,
    val body: String,
)

/** `chat-message` — activity sent while the recipient does not have the channel open. */
@Serializable
data class ChatMessageNotification(
    val appName: String = "Bosca",
    val logoUrl: String = "",
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    val senderName: String,
    /** Group-channel display name. Direct-message producers leave this empty. */
    val channelName: String = "",
    /** True when the conversation is one-to-one and the channel's internal name must stay hidden. */
    val direct: Boolean = false,
    /** Source channel used to retrieve the message only while this template renders. */
    val channelId: String,
    /** Source message sequence used to retrieve the message only while this template renders. */
    val sequence: Long,
    val actionUrl: String,
)

/** `chat-reaction` — push activity for a reaction added to the recipient's chat message. */
@Serializable
data class ChatReactionNotification(
    val appName: String = "Bosca",
    val logoUrl: String = "",
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    val reactorName: String,
    /** Source channel used to retrieve the reaction only while this template renders. */
    val channelId: String,
    /** Source message sequence used to retrieve the reaction only while this template renders. */
    val sequence: Long,
    /** Stable source reaction identifier; the emoji itself is deliberately absent from this payload. */
    val reactionId: String,
    val actionUrl: String,
)

/** `prayer-reaction` — push activity after another profile prays for or likes a prayer. */
@Serializable
data class PrayerReactionNotification(
    val appName: String = "Bosca",
    val logoUrl: String = "",
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    val actorName: String,
    /** `PRAYED` or `LIKED`. */
    val reaction: String,
    val actionUrl: String,
)

/** `prayer-comment` — push activity for a new prayer comment or reply. */
@Serializable
data class PrayerCommentNotification(
    val appName: String = "Bosca",
    val logoUrl: String = "",
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    val actorName: String,
    /** True when the recipient authored the comment being replied to. */
    val reply: Boolean = false,
    val actionUrl: String,
)

/** `channel-invitation` — an invitation to join a chat channel. */
@Serializable
data class ChannelInvitationNotification(
    val appName: String = "Bosca",
    val logoUrl: String = "",
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    val inviterName: String,
    val channelName: String,
    val actionUrl: String,
)

/** `channel-joined` — a profile newly became a member of a chat channel. */
@Serializable
data class ChannelJoinedNotification(
    val appName: String = "Bosca",
    val logoUrl: String = "",
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    val joinedProfileName: String,
    val channelName: String,
    val actionUrl: String,
)

/** `relationship-request` — one profile requested a relationship with the recipient. */
@Serializable
data class RelationshipRequestNotification(
    val appName: String = "Bosca",
    val logoUrl: String = "",
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    val requesterName: String,
    val actionUrl: String,
)

/** `relationship-added` — a relationship was added for the recipient. */
@Serializable
data class RelationshipAddedNotification(
    val appName: String = "Bosca",
    val logoUrl: String = "",
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
    val relatedProfileName: String,
    val actionUrl: String,
)

const val DEFAULT_PRIMARY_COLOR = "#0e1019"
const val DEFAULT_ACCENT_COLOR = "#047a52"
