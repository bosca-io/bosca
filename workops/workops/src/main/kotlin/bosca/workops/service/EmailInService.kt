package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.emailin.EmailInbox
import bosca.workops.model.emailin.InboundEmail
import bosca.workops.model.emailin.InboundEmailOutcome
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.repository.EmailInboxInsertParams
import bosca.workops.repository.EmailInboxRepository
import bosca.workops.repository.InboundEmailInsertParams
import bosca.workops.repository.InboundEmailRepository
import bosca.workops.repository.OutboundMessageIdRepository
import org.slf4j.LoggerFactory

@ServiceImplementation
class EmailInboxServiceImpl(
    private val repository: EmailInboxRepository,
) : EmailInboxService {
    override suspend fun list() = repository.listAll()
    override suspend fun listForProject(projectId: UUID) = repository.listForProject(projectId)
    override suspend fun getById(id: UUID) = repository.getById(id)
    override suspend fun getByAddress(address: String) = repository.getByAddress(address)
    override suspend fun create(input: CreateEmailInboxInput): EmailInbox = repository.add(
        EmailInboxInsertParams(
            name = input.name, description = input.description,
            kind = input.kind.name, address = input.address,
            projectId = input.projectId,
            defaultTaskTypeId = input.defaultTaskTypeId,
            defaultPriorityId = input.defaultPriorityId,
            enabled = input.enabled, hmacSecret = input.hmacSecret,
        )
    )
}

@ServiceImplementation
class OutboundMessageIdServiceImpl(
    private val repository: OutboundMessageIdRepository,
) : OutboundMessageIdService {
    override suspend fun register(messageId: String, taskId: UUID, commentId: Long?) =
        repository.add(messageId, taskId, commentId)

    override suspend fun resolve(messageId: String) = repository.resolve(messageId)
}

@ServiceImplementation
class EmailInProcessorImpl(
    private val inboxRepository: EmailInboxRepository,
    private val auditRepository: InboundEmailRepository,
    private val outboundRepository: OutboundMessageIdRepository,
    private val taskService: TaskService,
    private val taskCommentService: TaskCommentService,
) : EmailInProcessor {

    private val log = LoggerFactory.getLogger(EmailInProcessorImpl::class.java)

    override suspend fun process(inboxId: UUID, message: InboundMessage): InboundEmail {
        val inbox = inboxRepository.getById(inboxId)
            ?: throw WorkOpsNotFoundException("EmailInbox", inboxId.toString())

        // Auto-responder loop guard.
        val rawAutoSubmitted = message.autoSubmitted
        val autoSubmitted = if (rawAutoSubmitted == null) null else rawAutoSubmitted.lowercase().trim()
        if (!autoSubmitted.isNullOrEmpty() && autoSubmitted != "no") {
            return audit(
                inbox, message,
                outcome = InboundEmailOutcome.DLQ_LOOP_GUARD,
                taskId = null, commentId = null,
                errorMessage = "Auto-Submitted: $autoSubmitted",
            )
        }
        // Sender verification.
        if (!message.verifiedSender) {
            return audit(
                inbox, message,
                outcome = InboundEmailOutcome.DLQ_UNVERIFIED_SENDER,
                taskId = null, commentId = null,
                errorMessage = "SPF/DKIM verification failed",
            )
        }
        // In-Reply-To match → comment append.
        val rawInReplyTo = message.inReplyTo
        val inReplyTo = if (rawInReplyTo == null) null else rawInReplyTo.trim().removeSurrounding("<", ">")
        val resolved = if (inReplyTo == null) null else outboundRepository.resolve(inReplyTo)
        return if (resolved != null) {
            val comment = runCatching {
                taskCommentService.add(
                    taskId = resolved.taskId,
                    input = bosca.workops.model.comment.TaskCommentInput(
                        content = "from ${message.fromAddress}:\n\n${message.bodyMarkdown}",
                    ),
                    actingPrincipalId = UUID.NIL,
                    actingProfileId = UUID.NIL,
                )
            }
            comment.fold(
                onSuccess = { savedComment ->
                    log.warn(
                        "Email-in: appended comment (id={}) on task {} from={} using system principal UUID.NIL; inbox={}",
                        savedComment.id, resolved.taskId, message.fromAddress, inbox.id,
                    )
                    audit(
                        inbox, message,
                        outcome = InboundEmailOutcome.APPENDED_COMMENT,
                        taskId = resolved.taskId,
                        commentId = savedComment.id,
                        errorMessage = null,
                    )
                },
                onFailure = { error ->
                    audit(
                        inbox, message,
                        outcome = InboundEmailOutcome.DLQ_PARSE_FAILURE,
                        taskId = resolved.taskId,
                        commentId = null,
                        errorMessage = error.message,
                    )
                },
            )
        } else {
            // No matching reply-to → create a fresh task in the inbox's project.
            val task = runCatching {
                taskService.create(
                    input = CreateTaskInput(
                        projectId = inbox.projectId,
                        summary = message.subject ?: "(no subject)",
                        descriptionMarkdown = message.bodyMarkdown,
                        taskTypeId = inbox.defaultTaskTypeId,
                        priorityId = inbox.defaultPriorityId,
                    ),
                    actingPrincipalId = UUID.NIL,
                    actingProfileId = null,
                    reporterProfileId = UUID.NIL,
                )
            }
            task.fold(
                onSuccess = { savedTask ->
                    log.warn(
                        "Email-in: created task {} from={} using system principal UUID.NIL; inbox={}, project={}",
                        savedTask.id, message.fromAddress, inbox.id, inbox.projectId,
                    )
                    audit(
                        inbox, message,
                        outcome = InboundEmailOutcome.NEW_TASK,
                        taskId = savedTask.id,
                        commentId = null,
                        errorMessage = null,
                    )
                },
                onFailure = { error ->
                    audit(
                        inbox, message,
                        outcome = InboundEmailOutcome.DLQ_PARSE_FAILURE,
                        taskId = null, commentId = null,
                        errorMessage = error.message,
                    )
                },
            )
        }
    }

    private suspend fun audit(
        inbox: EmailInbox,
        message: InboundMessage,
        outcome: InboundEmailOutcome,
        taskId: UUID?,
        commentId: Long?,
        errorMessage: String?,
    ): InboundEmail = auditRepository.add(
        InboundEmailInsertParams(
            inboxId = inbox.id,
            messageId = message.messageId,
            inReplyTo = message.inReplyTo,
            fromAddress = message.fromAddress,
            subject = message.subject,
            outcome = outcome.name,
            taskId = taskId,
            commentId = commentId,
            errorMessage = errorMessage,
            rawMime = message.rawMime,
        )
    )
}
