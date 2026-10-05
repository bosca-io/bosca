package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.emailin.EmailInbox
import bosca.workops.model.emailin.EmailInboxKind
import bosca.workops.model.emailin.InboundEmail
import bosca.workops.model.emailin.InboundEmailOutcome
import bosca.workops.model.emailin.OutboundMessageId
import bosca.workops.repository.EmailInboxInsertParams
import bosca.workops.repository.EmailInboxRepository
import bosca.workops.repository.InboundEmailInsertParams
import bosca.workops.repository.InboundEmailRepository
import bosca.workops.repository.OutboundMessageIdRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EmailInServiceTest {

    @Test
    fun `inbox lookups and creation delegate every field`() = runTest {
        val repository = mockk<EmailInboxRepository>()
        val inbox = sampleInbox()
        val input = CreateEmailInboxInput(
            name = inbox.name,
            description = inbox.description,
            kind = inbox.kind,
            address = inbox.address,
            projectId = inbox.projectId,
            defaultTaskTypeId = inbox.defaultTaskTypeId,
            defaultPriorityId = inbox.defaultPriorityId,
            enabled = inbox.enabled,
            hmacSecret = inbox.hmacSecret,
        )
        coEvery { repository.listAll() } returns listOf(inbox)
        coEvery { repository.listForProject(inbox.projectId) } returns listOf(inbox)
        coEvery { repository.getById(inbox.id) } returns inbox
        coEvery { repository.getByAddress(inbox.address) } returns inbox
        coEvery { repository.add(any()) } coAnswers {
            val params = firstArg<EmailInboxInsertParams>()
            assertEquals(input.name, params.name)
            assertEquals(input.description, params.description)
            assertEquals(input.kind.name, params.kind)
            assertEquals(input.address, params.address)
            assertEquals(input.projectId, params.projectId)
            assertEquals(input.defaultTaskTypeId, params.defaultTaskTypeId)
            assertEquals(input.defaultPriorityId, params.defaultPriorityId)
            assertEquals(input.enabled, params.enabled)
            assertEquals(input.hmacSecret, params.hmacSecret)
            inbox
        }
        val service = EmailInboxServiceImpl(repository)

        assertEquals(listOf(inbox), service.list())
        assertEquals(listOf(inbox), service.listForProject(inbox.projectId))
        assertEquals(inbox, service.getById(inbox.id))
        assertEquals(inbox, service.getByAddress(inbox.address))
        assertEquals(inbox, service.create(input))
    }

    @Test
    fun `processor reports a missing inbox before dispatching work`() = runTest {
        val inboxRepository = mockk<EmailInboxRepository>()
        val auditRepository = mockk<InboundEmailRepository>()
        val outboundRepository = mockk<OutboundMessageIdRepository>()
        val taskService = mockk<TaskService>()
        val taskCommentService = mockk<TaskCommentService>()
        val inboxId = UUID.random()
        coEvery { inboxRepository.getById(inboxId) } returns null
        val processor = EmailInProcessorImpl(
            inboxRepository,
            auditRepository,
            outboundRepository,
            taskService,
            taskCommentService,
        )

        val failure = assertFailsWith<WorkOpsNotFoundException> {
            processor.process(inboxId, sampleMessage())
        }

        assertEquals("EmailInbox", failure.type)
        assertEquals(inboxId.toString(), failure.handle)
        coVerify(exactly = 0) { auditRepository.add(any()) }
    }

    @Test
    fun `processor audits a parse failure when a threaded comment cannot be added`() = runTest {
        val inboxRepository = mockk<EmailInboxRepository>()
        val auditRepository = mockk<InboundEmailRepository>()
        val outboundRepository = mockk<OutboundMessageIdRepository>()
        val taskService = mockk<TaskService>()
        val taskCommentService = mockk<TaskCommentService>()
        val inbox = sampleInbox()
        val taskId = UUID.random()
        coEvery { inboxRepository.getById(inbox.id) } returns inbox
        coEvery { outboundRepository.resolve("thread@example.com") } returns
                OutboundMessageId("thread@example.com", taskId)
        coEvery { taskCommentService.add(any(), any(), any(), any(), any()) } throws
                IllegalStateException("comment rejected")
        stubAudit(auditRepository)
        val processor = EmailInProcessorImpl(
            inboxRepository,
            auditRepository,
            outboundRepository,
            taskService,
            taskCommentService,
        )

        val result = processor.process(
            inbox.id,
            sampleMessage(inReplyTo = " <thread@example.com> ", autoSubmitted = " NO "),
        )

        assertEquals(InboundEmailOutcome.DLQ_PARSE_FAILURE, result.outcome)
        assertEquals(taskId, result.taskId)
        assertNull(result.commentId)
        assertEquals("comment rejected", result.errorMessage)
        coVerify(exactly = 0) { taskService.create(any(), any(), any(), any()) }
    }

    @Test
    fun `processor audits a parse failure when a new task cannot be created`() = runTest {
        val inboxRepository = mockk<EmailInboxRepository>()
        val auditRepository = mockk<InboundEmailRepository>()
        val outboundRepository = mockk<OutboundMessageIdRepository>()
        val taskService = mockk<TaskService>()
        val taskCommentService = mockk<TaskCommentService>()
        val inbox = sampleInbox()
        coEvery { inboxRepository.getById(inbox.id) } returns inbox
        coEvery { taskService.create(any(), any(), any(), any()) } throws IllegalArgumentException("task rejected")
        stubAudit(auditRepository)
        val processor = EmailInProcessorImpl(
            inboxRepository,
            auditRepository,
            outboundRepository,
            taskService,
            taskCommentService,
        )

        val result = processor.process(inbox.id, sampleMessage(subject = null, autoSubmitted = ""))

        assertEquals(InboundEmailOutcome.DLQ_PARSE_FAILURE, result.outcome)
        assertNull(result.taskId)
        assertNull(result.commentId)
        assertEquals("task rejected", result.errorMessage)
        coVerify(exactly = 0) { taskCommentService.add(any(), any(), any(), any(), any()) }
    }

    private fun stubAudit(repository: InboundEmailRepository) {
        coEvery { repository.add(any()) } coAnswers {
            val input = firstArg<InboundEmailInsertParams>()
            InboundEmail(
                id = UUID.random(),
                inboxId = input.inboxId,
                messageId = input.messageId,
                inReplyTo = input.inReplyTo,
                fromAddress = input.fromAddress,
                subject = input.subject,
                outcome = InboundEmailOutcome.valueOf(input.outcome),
                taskId = input.taskId,
                commentId = input.commentId,
                errorMessage = input.errorMessage,
                rawMime = input.rawMime,
            )
        }
    }

    private fun sampleInbox() = EmailInbox(
        id = UUID.random(),
        name = "Support",
        description = "Customer support",
        kind = EmailInboxKind.POSTMARK_WEBHOOK,
        address = "support@example.com",
        projectId = UUID.random(),
        defaultTaskTypeId = UUID.random(),
        defaultPriorityId = UUID.random(),
        enabled = true,
        hmacSecret = "secret",
    )

    private fun sampleMessage(
        inReplyTo: String? = null,
        subject: String? = "Help",
        autoSubmitted: String? = null,
    ) = InboundMessage(
        messageId = "<incoming@example.com>",
        inReplyTo = inReplyTo,
        fromAddress = "customer@example.com",
        subject = subject,
        bodyMarkdown = "Please help",
        rawMime = "raw message",
        autoSubmitted = autoSubmitted,
    )
}
