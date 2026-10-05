package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.emailin.EmailInbox
import bosca.workops.model.emailin.EmailInboxKind
import bosca.workops.model.emailin.InboundEmail
import bosca.workops.model.emailin.OutboundMessageId

interface EmailInboxService : Service {
    suspend fun list(): List<EmailInbox>
    suspend fun listForProject(projectId: UUID): List<EmailInbox>
    suspend fun getById(id: UUID): EmailInbox?
    suspend fun getByAddress(address: String): EmailInbox?
    suspend fun create(input: CreateEmailInboxInput): EmailInbox
}

data class CreateEmailInboxInput(
    val name: String,
    val description: String?,
    val kind: EmailInboxKind,
    val address: String,
    val projectId: UUID,
    val defaultTaskTypeId: UUID?,
    val defaultPriorityId: UUID?,
    val enabled: Boolean = true,
    val hmacSecret: String? = null,
)

interface OutboundMessageIdService : Service {
    /** R30 — registers a Message-ID stamped on a task notification email. */
    suspend fun register(messageId: String, taskId: UUID, commentId: Long? = null): OutboundMessageId?
    /** Resolves a (potentially-stripped) `In-Reply-To` reference to the originating task. */
    suspend fun resolve(messageId: String): OutboundMessageId?
}

/**
 * R30 — already-parsed inbound message handed in from a transport
 * adapter (SMTP fetcher, IMAP fetcher, or a webhook receiver).
 * The processor runs the same flow regardless of source.
 */
data class InboundMessage(
    val messageId: String?,
    val inReplyTo: String?,
    val fromAddress: String,
    val subject: String?,
    val bodyMarkdown: String,
    val rawMime: String? = null,
    /** RFC 3834 auto-responder header — when present, the loop guard rejects. */
    val autoSubmitted: String? = null,
    /** SPF/DKIM verification results from the transport. */
    val verifiedSender: Boolean = true,
)

interface EmailInProcessor : Service {
    /**
     * R30 — drives one inbound message through the standard
     * pipeline: loop guard → sender verification → reply-thread
     * lookup → task/comment dispatch → audit row.
     *
     * Tasks and comments created through the email-in pipeline are
     * attributed to [UUID.NIL] (the "system" principal) because the
     * inbound sender is an external email address that has no
     * corresponding Bosca principal. The audit row (`inbound_email`)
     * captures the original `fromAddress` for traceability. All
     * mutations performed under this principal are logged at WARN
     * level so they can be correlated in production logs.
     */
    suspend fun process(inboxId: UUID, message: InboundMessage): InboundEmail
}
