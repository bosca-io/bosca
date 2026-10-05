package bosca.messages

import bosca.bml.email.RenderedEmail
import bosca.bml.graphql.GraphQLClient
import bosca.bml.message.BmlMessageContext
import bosca.bml.message.BmlPushAction
import bosca.bml.message.BmlPushOptions
import bosca.bml.message.RenderedMessage
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One preview variant on the index: a template key + the sample context it renders with. */
data class PreviewEntry(
    val slug: String,
    val number: String,
    val title: String,
    val description: String,
    val templateKey: String,
    val context: () -> BmlMessageContext,
)

/**
 * The design-review sample data. Production contexts are assembled by the communications send
 * path; these exist only for the preview site and the render tests.
 */
object Samples {

    /**
     * Absolute asset base for preview renders — emails have no origin, so even previews build
     * every asset URL from an absolute base. The default is the preview server's own origin
     * (it serves `public/` at the root); production sends get a version-pinned base from the
     * message server (`<public base>/assets/<project>/<version>`).
     */
    val assetsUrl: String = System.getenv("BML_MESSAGE_ASSETS_URL") ?: "http://localhost:4567"

    private fun <T> context(
        serializer: SerializationStrategy<T>,
        payload: T,
        recipientName: String? = "Sarah",
        defaultActionId: String? = null,
        gql: GraphQLClient? = null,
    ) = BmlMessageContext(
        recipientName = recipientName,
        recipientEmail = "sarah@example.com",
        unsubscribeUrl = "#unsubscribe-sample",
        preferencesUrl = "#preferences-sample",
        assetsUrl = assetsUrl,
        payload = Json.encodeToJsonElement(serializer, payload),
        gql = gql,
        pushOptions = defaultActionId?.let { id ->
            BmlPushOptions(defaultAction = BmlPushAction(id = id, url = "#action-sample"))
        },
    )

    private val welcome = Welcome(
        preheader = "Your Bosca account is ready.",
        getStartedUrl = "#get-started",
        highlights = listOf(
            Highlight("Publish structured content", "Author documents, collections, and media in Studio, and deliver them to every site and app you run."),
            Highlight("Automate the busywork", "Pipelines react to publishes, submissions, and schedules — the routine steps run themselves."),
            Highlight("See what's working", "Analytics ties every view and interaction back to the content that earned it."),
        ),
    )

    private val verifyEmail = VerifyEmail(
        verifyUrl = "https://example.com/verify?token=sample-token",
        expiresIn = "24 hours",
    )

    private val resetPassword = ResetPassword(
        resetUrl = "https://example.com/reset-password?token=sample-token",
        expiresIn = "1 hour",
    )

    private val securityAlertSignIn = SecurityAlert(
        event = "New sign-in to your account",
        time = "Jul 17, 2026 at 2:41 PM (EDT)",
        details = listOf(
            LabeledValue("Device", "MacBook Pro · Safari 17"),
            LabeledValue("Location", "Atlanta, GA, United States"),
            LabeledValue("IP address", "203.0.113.42"),
        ),
        reviewUrl = "#security",
    )

    private val securityAlertPassword = SecurityAlert(
        event = "Your password was changed",
        time = "Jul 17, 2026 at 2:41 PM (EDT)",
        reviewUrl = "#security",
    )

    private val accountLink = AccountLink(
        confirmUrl = "https://studio.example.com/auth/link/confirm?proof=sample-proof",
    )

    private val formSubmission = FormSubmission(
        formName = "Contact Us",
        subject = "New contact request",
        submitterName = "Jordan Avery",
        submitterEmail = "jordan@example.com",
        submittedAt = "Jul 17, 2026 · 2:41 PM",
        fields = listOf(
            LabeledValue("Name", "Jordan Avery"),
            LabeledValue("Email", "jordan@example.com"),
            LabeledValue("Company", "Northwind Publishing"),
            LabeledValue(
                "Message",
                "We're evaluating platforms for a network of content sites and would love a walkthrough " +
                    "of how Bosca handles multi-site publishing, roles, and workflows. Mornings work best.",
            ),
        ),
        reviewUrl = "#review",
    )

    private val formSubmissionEmpty = FormSubmission(
        formName = "Newsletter Signup",
        submittedAt = "Jul 17, 2026 · 9:03 AM",
        reviewUrl = "#review",
    )

    private val formSubmissionReceipt = FormSubmissionReceipt(
        formName = "Speaker Application",
        submittedAt = "Jul 17, 2026 · 2:41 PM",
        fields = listOf(
            LabeledValue("Name", "Sarah Lin"),
            LabeledValue("Talk title", "Composable content at scale"),
            LabeledValue("Format", "30-minute session"),
        ),
        nextSteps = "We review applications weekly — expect to hear from us within a few days.",
    )

    private val formSubmissionReceiptBare = FormSubmissionReceipt(
        formName = "Newsletter Signup",
        submittedAt = "Jul 17, 2026 · 9:03 AM",
    )

    private val gitPullRequest = GitPullRequestNotification(
        repositoryName = "bosca-workspace",
        number = 79,
        title = "Send Git activity through email pipelines",
        action = "COMMENTED",
        actorName = "Maya Rivera",
        sourceBranch = "feature/git-email-pipelines",
        targetBranch = "main",
        body = "The event payload should include task keys so the email can preserve the WorkOps context.",
        filePath = "git/core-git/src/main/kotlin/bosca/git/model/GitEvent.kt",
        lineNumber = 48,
        taskKeys = listOf("GIT-79"),
        pullRequestUrl = "https://studio.example.com/git/pulls/sample?repo=sample&number=79",
    )

    private val gitRefUpdate = GitRefUpdateNotification(
        repositoryName = "bosca-workspace",
        refName = "feature/git-email-pipelines",
        kind = "BRANCH",
        action = "UPDATED",
        beforeSha = "a4d9e8f21b778f2a5b7c0957a5a3c42d8cf2bc41",
        afterSha = "b86b58c20728dc902ea0ee21ce99a53542e5408a",
        taskKeys = listOf("GIT-79"),
        commitMessages = listOf(
            "Emit pull request activity events",
            "Add Git notification email templates",
        ),
        repositoryUrl = "https://studio.example.com/git/repositories/sample",
    )

    private val workOpsNotification = WorkOpsNotification(
        event = "TASK_COMMENTED",
        projectName = "Bosca Platform",
        entityType = "TASK",
        entityKey = "COMMS-37",
        title = "Route transactional emails through pipelines",
        body = "The security and WorkOps email paths should emit typed events and let seeded pipelines render and deliver them.",
        actorName = "Maya Rivera",
        actionUrl = "https://studio.example.com/workops/tasks/sample",
    )

    private val workOpsAutomation = WorkOpsAutomationEmail(
        subject = "Deployment follow-up required",
        body = "The production deployment failed. Review the run and assign an owner before retrying.",
    )

    private val chatMessage = ChatMessageNotification(
        senderName = "Maya Rivera",
        channelName = "Launch planning",
        channelId = SAMPLE_CHAT_CHANNEL_ID,
        sequence = 42,
        actionUrl = "https://app.example/chat/channel-id?sequence=42",
    )

    private val chatMessageGraphQL = object : GraphQLClient {
        override suspend fun execute(
            query: String,
            variables: JsonObject?,
            operationName: String?,
            token: String?,
        ): JsonElement = Json.parseToJsonElement(
            """
            {
              "chat": {
                "channel": {
                  "messages": [{
                    "sequence": 42,
                    "deleted": false,
                    "content": [{
                      "type": "TEXT",
                      "content": "I updated the rollout checklist and added the final review notes."
                    }]
                  }]
                }
              }
            }
            """.trimIndent(),
        )
    }

    private val channelInvitation = ChannelInvitationNotification(
        inviterName = "Maya Rivera",
        channelName = "Launch planning",
        actionUrl = "https://app.example/chat/invitations/invitation-id",
    )

    private val channelJoined = ChannelJoinedNotification(
        joinedProfileName = "Jordan Avery",
        channelName = "Launch planning",
        actionUrl = "https://app.example/chat/channel-id",
    )

    private val relationshipRequest = RelationshipRequestNotification(
        requesterName = "Maya Rivera",
        actionUrl = "https://app.example/relationships/requests",
    )

    private val relationshipAdded = RelationshipAddedNotification(
        relatedProfileName = "Maya Rivera",
        actionUrl = "https://app.example/profiles/profile-id",
    )

    val entries: List<PreviewEntry> = listOf(
        PreviewEntry(
            "01-welcome", "1", "Welcome",
            "Account created · get-started CTA + what-you-can-do rows",
            "welcome",
        ) { context(Welcome.serializer(), welcome) },
        PreviewEntry(
            "01b-welcome-noname", "1b", "Welcome - no name",
            "Fallback when the first name is unknown · headline reads \"Welcome.\"",
            "welcome",
        ) { context(Welcome.serializer(), welcome, recipientName = null) },
        PreviewEntry(
            "02-verify-email", "2", "Verify Email",
            "Email-verification challenge · verify CTA, expiry, paste fallback",
            "verify-email",
        ) { context(VerifyEmail.serializer(), verifyEmail) },
        PreviewEntry(
            "03-reset-password", "3", "Reset Password",
            "Account recovery · reset CTA, safe-to-ignore note",
            "reset-password",
        ) { context(ResetPassword.serializer(), resetPassword) },
        PreviewEntry(
            "04-security-alert", "4a", "Security Alert - new sign-in",
            "Sign-in event with device, location, and IP rows",
            "security-alert",
        ) { context(SecurityAlert.serializer(), securityAlertSignIn) },
        PreviewEntry(
            "04b-security-alert-password", "4b", "Security Alert - password changed",
            "Event with no detail rows · the panel stays tight",
            "security-alert",
        ) { context(SecurityAlert.serializer(), securityAlertPassword) },
        PreviewEntry(
            "05-form-submission", "5a", "Form Submission",
            "Reviewer notification · submitter, field table, review CTA",
            "form-submission",
        ) { context(FormSubmission.serializer(), formSubmission) },
        PreviewEntry(
            "05b-form-submission-empty", "5b", "Form Submission - anonymous, no fields",
            "Anonymous submitter · explicit empty state",
            "form-submission",
        ) { context(FormSubmission.serializer(), formSubmissionEmpty) },
        PreviewEntry(
            "06-form-receipt", "6a", "Submission Receipt",
            "Submitter confirmation · what-you-sent echo + next steps",
            "form-submission-receipt",
        ) { context(FormSubmissionReceipt.serializer(), formSubmissionReceipt) },
        PreviewEntry(
            "06b-form-receipt-bare", "6b", "Submission Receipt - bare",
            "No echo, no next steps · just the confirmation",
            "form-submission-receipt",
        ) { context(FormSubmissionReceipt.serializer(), formSubmissionReceiptBare) },
        PreviewEntry(
            "07-git-pull-request", "7", "Git Pull Request Activity",
            "Comment notification · repository context, diff location, task key, PR CTA",
            "git-pull-request",
        ) { context(GitPullRequestNotification.serializer(), gitPullRequest) },
        PreviewEntry(
            "08-git-ref-update", "8", "Git Ref Activity",
            "Branch update · before/after SHAs, commit summaries, task key, repository CTA",
            "git-ref-update",
        ) { context(GitRefUpdateNotification.serializer(), gitRefUpdate) },
        PreviewEntry(
            "09-account-link", "9", "Account Link",
            "Security confirmation · single CTA + unrequested-action guidance",
            "account-link",
        ) { context(AccountLink.serializer(), accountLink) },
        PreviewEntry(
            "10-workops-notification", "10", "WorkOps Activity",
            "Task comment · project and task context, comment body, WorkOps CTA",
            "workops-notification",
        ) { context(WorkOpsNotification.serializer(), workOpsNotification) },
        PreviewEntry(
            "11-workops-automation", "11", "WorkOps Automation",
            "Rule-authored subject and body · profile-addressed pipeline delivery",
            "workops-automation",
        ) { context(WorkOpsAutomationEmail.serializer(), workOpsAutomation) },
        PreviewEntry(
            "12-chat-message", "12", "Chat Message",
            "Message sent while the recipient does not have the channel open",
            "chat-message",
        ) {
            context(
                ChatMessageNotification.serializer(),
                chatMessage,
                defaultActionId = "open-chat",
                gql = chatMessageGraphQL,
            )
        },
        PreviewEntry(
            "13-channel-invitation", "13", "Channel Invitation",
            "A profile is invited to join a chat channel",
            "channel-invitation",
        ) { context(ChannelInvitationNotification.serializer(), channelInvitation, defaultActionId = "view-invitation") },
        PreviewEntry(
            "14-channel-joined", "14", "Channel Joined",
            "A profile newly becomes a channel member",
            "channel-joined",
        ) { context(ChannelJoinedNotification.serializer(), channelJoined, defaultActionId = "open-chat") },
        PreviewEntry(
            "15-relationship-request", "15", "Relationship Request",
            "A profile requests a relationship with the recipient",
            "relationship-request",
        ) { context(RelationshipRequestNotification.serializer(), relationshipRequest, defaultActionId = "view-request") },
        PreviewEntry(
            "16-relationship-added", "16", "Relationship Added",
            "A requested relationship is added",
            "relationship-added",
        ) { context(RelationshipAddedNotification.serializer(), relationshipAdded, defaultActionId = "view-profile") },
    )

    /** Render the preview variant [slug] through its compiled template, or null when unknown. */
    suspend fun render(slug: String): RenderedEmail? {
        return renderMessage(slug)?.email
    }

    /** Render every channel of preview variant [slug], or null when unknown. */
    suspend fun renderMessage(slug: String): RenderedMessage? {
        val entry = entries.firstOrNull { it.slug == slug } ?: return null
        val context = entry.context()
        return bml.generated.BmlMessages.byKey[entry.templateKey]?.renderMessage(context)
    }

    private const val SAMPLE_CHAT_CHANNEL_ID = "00000000-0000-0000-0000-000000000042"
}
