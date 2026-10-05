@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.server.installer

import bosca.communications.model.Message
import bosca.communications.model.NotificationTypeKeys
import bosca.communications.service.MessageService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.forms.events.FormEmailField
import bosca.forms.events.FormSubmissionEmailRequested
import bosca.pipelines.PipelineContext
import bosca.pipelines.PipelineExecutorImpl
import bosca.pipelines.model.Pipeline
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineService
import bosca.pipelines.service.requireCompleted
import bosca.security.events.AccountLinkEmailRequested
import bosca.security.events.EmailVerificationRequested
import bosca.security.events.PasswordResetEmailRequested
import bosca.security.events.SecurityAlertEmailRequested
import bosca.security.events.SecurityEmailDetail
import bosca.security.events.WelcomeEmailRequested
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.automation.AutomationEmailRequested
import bosca.workops.model.notification.WorkOpsEmailRequested
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Executes every installed non-Git email graph through the real pipeline engine. */
class DefaultTransactionalEmailPipelineExecutionTest {

    private val messageService = mockk<MessageService>()
    private val sent = mutableListOf<Message>()
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        sent.clear()
        coEvery { messageService.send(capture(sent)) } returns Unit
        provides<MessageService>(singleton = true) { messageService }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private suspend fun pipelines(): Map<String, Pipeline> {
        val captured = mutableListOf<Pipeline>()
        val pipelineService = mockk<PipelineService>()
        coEvery { pipelineService.getAll() } returns emptyList()
        coEvery { pipelineService.graphAsJsonElement(capture(captured)) } returns JsonObject(emptyMap())
        coEvery {
            pipelineService.save(
                id = any(), name = any(), description = any(), acceptedInputType = any(),
                triggered = any(), version = any(), graph = any(), tags = any(), key = any(),
                api = any(), public = any(), schedule = any(), maxConcurrentRuns = any(),
                maxRunsPerMinute = any(),
            )
        } returns mockk(relaxed = true)

        DefaultTransactionalEmailPipelinesInstaller(pipelineService, "https://studio.example/")
            .install(mockk(relaxed = true), mockk(relaxed = true))
        return captured.associateBy { it.name }
    }

    private data class Case(
        val pipelineName: String,
        val input: PipelineValue,
        val template: String,
        val recipientId: UUID,
        val notificationType: String,
        val payloadField: String,
        val payloadValue: String,
    )

    @Test
    fun `every non-git email pipeline sends its selected recipients and typed payload`() = runTest {
        val pipelineByName = pipelines()
        val accountRecipient = UUID.random()
        val reviewerRecipient = UUID.random()
        val receiptRecipient = UUID.random()
        val workOpsRecipient = UUID.random()
        val automationRecipient = UUID.random()
        val submissionId = UUID.random()
        val formSchemaId = UUID.random()
        val workOpsEntityId = UUID.random()
        val formEvent = FormSubmissionEmailRequested(
            submissionId = submissionId,
            formSchemaId = formSchemaId,
            reviewerRecipientIds = setOf(reviewerRecipient),
            receiptRecipientIds = setOf(receiptRecipient),
            sendReceipt = true,
            formName = "Contact Us",
            subject = "New contact request",
            submitterName = "Jordan Avery",
            submitterEmail = "jordan@example.com",
            submittedAt = "2026-07-31T12:00:00Z",
            fields = listOf(FormEmailField("Message", "Hello")),
            reviewPath = "/forms/submissions?id=$submissionId",
            nextSteps = "We will follow up.",
        )

        val cases = listOf(
            Case(
                DefaultTransactionalEmailPipelinesInstaller.WELCOME_PIPELINE,
                PipelineValue.of(
                    WelcomeEmailRequested(setOf(accountRecipient), "https://studio.example/welcome"),
                    WelcomeEmailRequested.serializer(),
                ),
                "welcome", accountRecipient, NotificationTypeKeys.TRANSACTIONAL,
                "getStartedUrl", "https://studio.example/welcome",
            ),
            Case(
                DefaultTransactionalEmailPipelinesInstaller.VERIFY_EMAIL_PIPELINE,
                PipelineValue.of(
                    EmailVerificationRequested(setOf(accountRecipient), "https://studio.example/verify?token=v", "24 hours"),
                    EmailVerificationRequested.serializer(),
                ),
                "verify-email", accountRecipient, NotificationTypeKeys.TRANSACTIONAL,
                "verifyUrl", "https://studio.example/verify?token=v",
            ),
            Case(
                DefaultTransactionalEmailPipelinesInstaller.RESET_PASSWORD_PIPELINE,
                PipelineValue.of(
                    PasswordResetEmailRequested(setOf(accountRecipient), "https://studio.example/reset?token=r", "1 hour"),
                    PasswordResetEmailRequested.serializer(),
                ),
                "reset-password", accountRecipient, NotificationTypeKeys.TRANSACTIONAL,
                "resetUrl", "https://studio.example/reset?token=r",
            ),
            Case(
                DefaultTransactionalEmailPipelinesInstaller.ACCOUNT_LINK_PIPELINE,
                PipelineValue.of(
                    AccountLinkEmailRequested(setOf(accountRecipient), "https://studio.example/link?proof=p"),
                    AccountLinkEmailRequested.serializer(),
                ),
                "account-link", accountRecipient, NotificationTypeKeys.SECURITY,
                "confirmUrl", "https://studio.example/link?proof=p",
            ),
            Case(
                DefaultTransactionalEmailPipelinesInstaller.SECURITY_ALERT_PIPELINE,
                PipelineValue.of(
                    SecurityAlertEmailRequested(
                        setOf(accountRecipient),
                        "New sign-in to your account",
                        "2026-07-31T12:00:00Z",
                        listOf(SecurityEmailDetail("Method", "password")),
                        "https://studio.example/security",
                    ),
                    SecurityAlertEmailRequested.serializer(),
                ),
                "security-alert", accountRecipient, NotificationTypeKeys.SECURITY,
                "event", "New sign-in to your account",
            ),
            Case(
                DefaultTransactionalEmailPipelinesInstaller.FORM_SUBMISSION_PIPELINE,
                PipelineValue.of(formEvent, FormSubmissionEmailRequested.serializer()),
                "form-submission", reviewerRecipient, NotificationTypeKeys.TRANSACTIONAL,
                "subject", "New contact request",
            ),
            Case(
                DefaultTransactionalEmailPipelinesInstaller.FORM_RECEIPT_PIPELINE,
                PipelineValue.of(formEvent, FormSubmissionEmailRequested.serializer()),
                "form-submission-receipt", receiptRecipient, NotificationTypeKeys.TRANSACTIONAL,
                "nextSteps", "We will follow up.",
            ),
            Case(
                DefaultTransactionalEmailPipelinesInstaller.WORKOPS_PIPELINE,
                PipelineValue.of(
                    WorkOpsEmailRequested(
                        recipientIds = setOf(workOpsRecipient),
                        event = "TASK_COMMENTED",
                        projectName = "Bosca",
                        entityType = "TASK",
                        entityId = workOpsEntityId,
                        entityKey = "COMMS-37",
                        title = "Pipeline-backed email",
                        body = "Please use services.",
                        actorName = "Maya",
                        linkPath = "/workops/tasks/$workOpsEntityId",
                    ),
                    WorkOpsEmailRequested.serializer(),
                ),
                "workops-notification", workOpsRecipient, NotificationTypeKeys.WORKOPS_ACTIVITY,
                "actionUrl", "https://studio.example/workops/tasks/$workOpsEntityId",
            ),
            Case(
                DefaultTransactionalEmailPipelinesInstaller.WORKOPS_AUTOMATION_PIPELINE,
                PipelineValue.of(
                    AutomationEmailRequested(
                        recipientIds = setOf(automationRecipient),
                        subject = "Automation subject",
                        body = "Automation body",
                    ),
                    AutomationEmailRequested.serializer(),
                ),
                "workops-automation", automationRecipient, NotificationTypeKeys.WORKOPS_ACTIVITY,
                "subject", "Automation subject",
            ),
        )

        for ((index, case) in cases.withIndex()) {
            PipelineExecutorImpl().execute(
                pipelineByName.getValue(case.pipelineName),
                case.input,
                PipelineContext(AuthenticationContext(null, null), json),
            ).requireCompleted()

            assertEquals(index + 1, sent.size, case.template)
            val message = sent.last()
            val template = requireNotNull(message.bmlTemplate)
            val payload = requireNotNull(template.payload).jsonObject
            assertEquals(listOf(case.recipientId), message.recipients, case.template)
            assertEquals(case.notificationType, message.type, case.template)
            assertEquals("bosca-messages", template.project, case.template)
            assertEquals(case.template, template.templateKey, case.template)
            assertEquals(case.payloadValue, payload[case.payloadField]?.jsonPrimitive?.content, case.template)
        }
        assertEquals(cases.size, sent.size)
    }

    @Test
    fun `disabled form receipt stops before email delivery`() = runTest {
        val event = FormSubmissionEmailRequested(
            submissionId = UUID.random(),
            formSchemaId = UUID.random(),
            reviewerRecipientIds = emptySet(),
            receiptRecipientIds = setOf(UUID.random()),
            sendReceipt = false,
            formName = "Contact Us",
            submittedAt = "2026-07-31T12:00:00Z",
            reviewPath = "/forms/submissions",
        )

        PipelineExecutorImpl().execute(
            pipelines().getValue(DefaultTransactionalEmailPipelinesInstaller.FORM_RECEIPT_PIPELINE),
            PipelineValue.of(event, FormSubmissionEmailRequested.serializer()),
            PipelineContext(AuthenticationContext(null, null), json),
        ).requireCompleted()

        assertEquals(emptyList(), sent)
    }
}
