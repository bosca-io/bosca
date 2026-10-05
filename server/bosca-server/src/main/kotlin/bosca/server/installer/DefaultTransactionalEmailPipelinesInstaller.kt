package bosca.server.installer

import bosca.communications.model.NotificationTypeKeys
import bosca.forms.events.FormSubmissionEmailRequested
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.builtin.ConditionNode
import bosca.pipelines.builtin.JsonataNode
import bosca.pipelines.builtin.SendEmailTemplateNode
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.service.PipelineService
import bosca.security.events.AccountLinkEmailRequested
import bosca.security.events.EmailVerificationRequested
import bosca.security.events.PasswordResetEmailRequested
import bosca.security.events.SecurityAlertEmailRequested
import bosca.security.events.WelcomeEmailRequested
import bosca.serialization.UUID
import bosca.workops.model.automation.AutomationEmailRequested
import bosca.workops.model.notification.WorkOpsEmailRequested
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Seeds the editable event pipelines for every first-party Bosca email outside Git. Each event
 * carries profile recipients and domain data; the graph shapes the BML payload and delegates
 * preference-aware delivery to [SendEmailTemplateNode]. Existing pipelines retain operator edits;
 * installers only migrate obsolete first-party message-project references.
 */
class DefaultTransactionalEmailPipelinesInstaller(
    private val pipelineService: PipelineService,
    applicationUrl: String,
) : PackageInstaller {

    override val version: String = VERSION

    private val appUrlLiteral = Json.encodeToString(String.serializer(), applicationUrl.trimEnd('/'))

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val existingByName = pipelineService.getAll().associateBy { it.name }
        for (spec in specs()) {
            val existing = existingByName[spec.name]
            if (existing != null) {
                if (pipelineService.migrateDefaultEmailPipeline(existing)) {
                    log.info("migrated default email pipeline '{}' to the Bosca message project", spec.name)
                } else {
                    log.info("default email pipeline '{}' already present; skipping", spec.name)
                }
                continue
            }
            val pipeline = spec.pipeline()
            log.info("creating default email pipeline '{}' for {}", spec.name, spec.eventType)
            pipelineService.save(
                id = UUID.NIL,
                name = pipeline.name,
                description = pipeline.description,
                acceptedInputType = pipeline.acceptedInputType,
                tags = pipeline.tags,
                triggered = true,
                version = 0,
                graph = pipelineService.graphAsJsonElement(pipeline),
            )
        }
    }

    internal fun specs(): List<Spec> = listOf(
        Spec(
            name = WELCOME_PIPELINE,
            description = "Sends the welcome email after an account and delivery profile are created.",
            eventType = requireNotNull(WelcomeEmailRequested::class.qualifiedName),
            template = "welcome",
            payloadExpression = """{ "getStartedUrl": getStartedUrl }""",
        ),
        Spec(
            name = VERIFY_EMAIL_PIPELINE,
            description = "Sends an email-verification challenge for a profile email address.",
            eventType = requireNotNull(EmailVerificationRequested::class.qualifiedName),
            template = "verify-email",
            payloadExpression = """{ "verifyUrl": verifyUrl, "expiresIn": expiresIn }""",
        ),
        Spec(
            name = RESET_PASSWORD_PIPELINE,
            description = "Sends a password-reset link for an eligible account recovery request.",
            eventType = requireNotNull(PasswordResetEmailRequested::class.qualifiedName),
            template = "reset-password",
            payloadExpression = """{ "resetUrl": resetUrl, "expiresIn": expiresIn }""",
        ),
        Spec(
            name = ACCOUNT_LINK_PIPELINE,
            description = "Sends the security confirmation required to link a sign-in method.",
            eventType = requireNotNull(AccountLinkEmailRequested::class.qualifiedName),
            template = "account-link",
            notificationType = NotificationTypeKeys.SECURITY,
            payloadExpression = """{ "confirmUrl": confirmUrl }""",
        ),
        Spec(
            name = SECURITY_ALERT_PIPELINE,
            description = "Sends security alerts for sign-ins, password changes, and related account activity.",
            eventType = requireNotNull(SecurityAlertEmailRequested::class.qualifiedName),
            template = "security-alert",
            notificationType = NotificationTypeKeys.SECURITY,
            payloadExpression = """{
                "event": event,
                "time": time,
                "details": details,
                "reviewUrl": reviewUrl
            }""".trimIndent(),
        ),
        Spec(
            name = FORM_SUBMISSION_PIPELINE,
            description = "Sends a form-submission notification to the configured reviewer profiles.",
            eventType = requireNotNull(FormSubmissionEmailRequested::class.qualifiedName),
            template = "form-submission",
            recipientsExpression = "reviewerRecipientIds",
            conditionExpression = "${'$'}count(reviewerRecipientIds) > 0",
            payloadExpression = """{
                "formName": formName,
                "subject": subject,
                "submitterName": submitterName,
                "submitterEmail": submitterEmail,
                "submittedAt": submittedAt,
                "fields": fields,
                "reviewUrl": $appUrlLiteral & reviewPath
            }""".trimIndent(),
        ),
        Spec(
            name = FORM_RECEIPT_PIPELINE,
            description = "Sends a receipt to a form submitter when the form enables receipts.",
            eventType = requireNotNull(FormSubmissionEmailRequested::class.qualifiedName),
            template = "form-submission-receipt",
            recipientsExpression = "receiptRecipientIds",
            conditionExpression = "sendReceipt and ${'$'}count(receiptRecipientIds) > 0",
            payloadExpression = """{
                "formName": formName,
                "submittedAt": submittedAt,
                "fields": fields,
                "nextSteps": nextSteps
            }""".trimIndent(),
        ),
        Spec(
            name = WORKOPS_PIPELINE,
            description = "Sends WorkOps activity selected by notification schemes and per-profile channel preferences.",
            eventType = requireNotNull(WorkOpsEmailRequested::class.qualifiedName),
            template = "workops-notification",
            notificationType = NotificationTypeKeys.WORKOPS_ACTIVITY,
            payloadExpression = """{
                "event": event,
                "projectName": projectName,
                "entityType": entityType,
                "entityKey": entityKey,
                "title": title,
                "body": body,
                "actorName": actorName,
                "actionUrl": $appUrlLiteral & linkPath
            }""".trimIndent(),
        ),
        Spec(
            name = WORKOPS_AUTOMATION_PIPELINE,
            description = "Sends profile-addressed email authored by a WorkOps automation rule.",
            eventType = requireNotNull(AutomationEmailRequested::class.qualifiedName),
            template = "workops-automation",
            notificationType = NotificationTypeKeys.WORKOPS_ACTIVITY,
            payloadExpression = """{
                "subject": subject,
                "body": body
            }""".trimIndent(),
        ),
    )

    internal data class Spec(
        val name: String,
        val description: String,
        val eventType: String,
        val template: String,
        val notificationType: String = NotificationTypeKeys.TRANSACTIONAL,
        val recipientsExpression: String = "recipientIds",
        val conditionExpression: String = "${'$'}count(recipientIds) > 0",
        val payloadExpression: String,
    ) {
        fun pipeline(): Pipeline = Pipeline(
            id = UUID.NIL,
            name = name,
            description = description,
            acceptedInputType = eventType,
            tags = listOf("Email", "Notifications"),
            triggered = true,
            nodes = listOf(
                InputNode(id = INPUT, acceptedType = eventType, position = NodePosition(40.0, 140.0)),
                ConditionNode(
                    id = HAS_RECIPIENTS,
                    name = "Should send",
                    expression = conditionExpression,
                    position = NodePosition(250.0, 140.0),
                ),
                JsonataNode(
                    id = RECIPIENTS,
                    name = "Recipients",
                    expression = recipientsExpression,
                    outputKind = SlotKind.ARRAY,
                    position = NodePosition(480.0, 70.0),
                ),
                JsonataNode(
                    id = PAYLOAD,
                    name = "Email payload",
                    expression = payloadExpression,
                    outputKind = SlotKind.OBJECT,
                    outputType = "email:$MESSAGE_PROJECT/$template",
                    position = NodePosition(480.0, 210.0),
                ),
                SendEmailTemplateNode(
                    id = SEND,
                    name = "Send $template email",
                    project = MESSAGE_PROJECT,
                    template = template,
                    notificationType = notificationType,
                    position = NodePosition(760.0, 140.0),
                ),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = INPUT, target = HAS_RECIPIENTS),
                PipelineEdge(id = "e2", source = HAS_RECIPIENTS, sourcePort = "true", target = RECIPIENTS),
                PipelineEdge(id = "e3", source = HAS_RECIPIENTS, sourcePort = "true", target = PAYLOAD),
                PipelineEdge(id = "e4", source = RECIPIENTS, target = SEND, targetPort = "recipients"),
                PipelineEdge(id = "e5", source = PAYLOAD, target = SEND, targetPort = "payload"),
            ),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(DefaultTransactionalEmailPipelinesInstaller::class.java)

        const val NAME = "default-transactional-email-pipelines"
        const val VERSION = "1.2.0"
        const val WELCOME_PIPELINE = "Email Account Activity — Welcome"
        const val VERIFY_EMAIL_PIPELINE = "Email Account Activity — Verify Email"
        const val RESET_PASSWORD_PIPELINE = "Email Account Activity — Reset Password"
        const val ACCOUNT_LINK_PIPELINE = "Email Account Activity — Link Account"
        const val SECURITY_ALERT_PIPELINE = "Email Account Activity — Security Alert"
        const val FORM_SUBMISSION_PIPELINE = "Email Forms — Submission Review"
        const val FORM_RECEIPT_PIPELINE = "Email Forms — Submission Receipt"
        const val WORKOPS_PIPELINE = "Email WorkOps Activity"
        const val WORKOPS_AUTOMATION_PIPELINE = "Email WorkOps Automation"

        val TEMPLATE_KEYS = setOf(
            "welcome",
            "verify-email",
            "reset-password",
            "account-link",
            "security-alert",
            "form-submission",
            "form-submission-receipt",
            "workops-notification",
            "workops-automation",
        )

        private const val MESSAGE_PROJECT = "bosca-messages"
        private const val INPUT = "input"
        private const val HAS_RECIPIENTS = "hasRecipients"
        private const val RECIPIENTS = "recipients"
        private const val PAYLOAD = "payload"
        private const val SEND = "send"
    }
}
