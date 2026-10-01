package bosca.server.installer

import bosca.communications.model.NotificationTypeKeys
import bosca.forms.events.FormSubmissionEmailRequested
import bosca.pipelines.builtin.ConditionNode
import bosca.pipelines.builtin.JsonataNode
import bosca.pipelines.builtin.SendEmailTemplateNode
import bosca.pipelines.model.Pipeline
import bosca.pipelines.service.PipelineService
import bosca.security.events.AccountLinkEmailRequested
import bosca.security.events.EmailVerificationRequested
import bosca.security.events.PasswordResetEmailRequested
import bosca.security.events.SecurityAlertEmailRequested
import bosca.security.events.WelcomeEmailRequested
import bosca.workops.model.automation.AutomationEmailRequested
import bosca.workops.model.notification.WorkOpsEmailRequested
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultTransactionalEmailPipelinesInstallerTest {

    private fun installer(
        existing: List<String> = emptyList(),
    ): Pair<DefaultTransactionalEmailPipelinesInstaller, MutableList<Pipeline>> {
        val captured = mutableListOf<Pipeline>()
        val pipelines = mockk<PipelineService>()
        coEvery { pipelines.getAll() } returns existing.map { name ->
            mockk<Pipeline> { every { this@mockk.name } returns name }
        }
        coEvery { pipelines.graphAsJsonElement(any()) } answers {
            firstArg<Pipeline>().also { pipeline ->
                if (pipeline.name !in existing) captured += pipeline
            }
            JsonObject(emptyMap())
        }
        coEvery {
            pipelines.save(
                id = any(), name = any(), description = any(), acceptedInputType = any(),
                triggered = any(), version = any(), graph = any(), tags = any(), key = any(),
                api = any(), public = any(), schedule = any(), maxConcurrentRuns = any(),
                maxRunsPerMinute = any(),
            )
        } returns mockk(relaxed = true)
        return DefaultTransactionalEmailPipelinesInstaller(pipelines, "https://studio.example/") to captured
    }

    @Test
    fun `seeds one triggered pipeline for every non-git email contract`() = runTest {
        val (installer, captured) = installer()
        installer.install(mockk(relaxed = true), mockk(relaxed = true))

        assertEquals(9, captured.size)
        assertEquals(
            setOf(
                WelcomeEmailRequested::class.qualifiedName,
                EmailVerificationRequested::class.qualifiedName,
                PasswordResetEmailRequested::class.qualifiedName,
                AccountLinkEmailRequested::class.qualifiedName,
                SecurityAlertEmailRequested::class.qualifiedName,
                FormSubmissionEmailRequested::class.qualifiedName,
                WorkOpsEmailRequested::class.qualifiedName,
                AutomationEmailRequested::class.qualifiedName,
            ),
            captured.map { it.acceptedInputType }.toSet(),
        )
        assertTrue(captured.all { it.triggered })
        assertTrue(captured.all { it.tags == listOf("Email", "Notifications") })

        for (pipeline in captured) {
            assertEquals(1, pipeline.nodes.filterIsInstance<ConditionNode>().size)
            assertEquals(2, pipeline.nodes.filterIsInstance<JsonataNode>().size)
            val send = pipeline.nodes.filterIsInstance<SendEmailTemplateNode>().single()
            assertEquals("bosca-messages", send.project)
            assertEquals(
                "email:bosca-messages/${send.template}",
                pipeline.nodes.filterIsInstance<JsonataNode>().single { it.id == "payload" }.outputType,
            )
            assertEquals(
                setOf("recipients", "payload"),
                pipeline.edges.filter { it.target == "send" }.mapNotNull { it.targetPort }.toSet(),
            )
        }
    }

    @Test
    fun `all Bosca email templates have exactly one seeded pipeline`() = runTest {
        val (installer, captured) = installer()
        installer.install(mockk(relaxed = true), mockk(relaxed = true))

        val nonGitTemplates = captured.map {
            it.nodes.filterIsInstance<SendEmailTemplateNode>().single().template
        }.toSet()
        assertEquals(DefaultTransactionalEmailPipelinesInstaller.TEMPLATE_KEYS, nonGitTemplates)
        assertEquals(
            setOf(
                "welcome",
                "verify-email",
                "reset-password",
                "account-link",
                "security-alert",
                "form-submission",
                "form-submission-receipt",
                "git-pull-request",
                "git-ref-update",
                "workops-notification",
                "workops-automation",
            ),
            nonGitTemplates + DefaultGitEmailPipelinesInstaller.TEMPLATE_KEYS,
        )
    }

    @Test
    fun `security and WorkOps pipelines use their dedicated notification types`() = runTest {
        val (installer, captured) = installer()
        installer.install(mockk(relaxed = true), mockk(relaxed = true))

        val typesByTemplate = captured.associate { pipeline ->
            val send = pipeline.nodes.filterIsInstance<SendEmailTemplateNode>().single()
            send.template to send.notificationType
        }
        assertEquals(NotificationTypeKeys.SECURITY, typesByTemplate.getValue("account-link"))
        assertEquals(NotificationTypeKeys.SECURITY, typesByTemplate.getValue("security-alert"))
        assertEquals(NotificationTypeKeys.WORKOPS_ACTIVITY, typesByTemplate.getValue("workops-notification"))
        assertEquals(NotificationTypeKeys.WORKOPS_ACTIVITY, typesByTemplate.getValue("workops-automation"))
        assertEquals(NotificationTypeKeys.TRANSACTIONAL, typesByTemplate.getValue("welcome"))
    }

    @Test
    fun `form pipelines select independent audiences and application deep link`() = runTest {
        val (installer, captured) = installer()
        installer.install(mockk(relaxed = true), mockk(relaxed = true))

        val review = captured.single { it.name == DefaultTransactionalEmailPipelinesInstaller.FORM_SUBMISSION_PIPELINE }
        assertEquals(
            "reviewerRecipientIds",
            review.nodes.filterIsInstance<JsonataNode>().single { it.id == "recipients" }.expression,
        )
        assertTrue(
            "\"https://studio.example\" & reviewPath" in
                review.nodes.filterIsInstance<JsonataNode>().single { it.id == "payload" }.expression,
        )
        assertTrue(
            "\"subject\": subject" in
                review.nodes.filterIsInstance<JsonataNode>().single { it.id == "payload" }.expression,
        )

        val receipt = captured.single { it.name == DefaultTransactionalEmailPipelinesInstaller.FORM_RECEIPT_PIPELINE }
        assertEquals(
            "receiptRecipientIds",
            receipt.nodes.filterIsInstance<JsonataNode>().single { it.id == "recipients" }.expression,
        )
        assertTrue(
            "sendReceipt and ${'$'}count(receiptRecipientIds) > 0" in
                receipt.nodes.filterIsInstance<ConditionNode>().single().expression,
        )
    }

    @Test
    fun `existing pipeline names are preserved`() = runTest {
        val (installer, captured) = installer(listOf(DefaultTransactionalEmailPipelinesInstaller.WELCOME_PIPELINE))
        installer.install(mockk(relaxed = true), mockk(relaxed = true))

        assertEquals(8, captured.size)
        assertTrue(captured.none { it.name == DefaultTransactionalEmailPipelinesInstaller.WELCOME_PIPELINE })
    }
}
