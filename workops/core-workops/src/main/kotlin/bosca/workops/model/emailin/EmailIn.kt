package bosca.workops.model.emailin

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * R30 — inbound email transport. Phase 15 ships SMTP_FETCH and
 * webhook receivers; the per-provider variants share the same
 * model and dispatch into the same processor.
 */
@Serializable
enum class EmailInboxKind {
    SMTP_FETCH,
    IMAP_FETCH,
    POSTMARK_WEBHOOK,
    SENDGRID_WEBHOOK,
    SES_WEBHOOK,
    MAILGUN_WEBHOOK,
}

/**
 * R30 — outcome of processing one inbound email.
 */
@Serializable
enum class InboundEmailOutcome {
    NEW_TASK,
    APPENDED_COMMENT,
    DLQ_PARSE_FAILURE,
    DLQ_LOOP_GUARD,
    DLQ_SPAM,
    DLQ_UNVERIFIED_SENDER,
}

@Serializable
data class EmailInbox(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String? = null,
    val kind: EmailInboxKind,
    /** SMTP/IMAP host, webhook URL slug, etc. — opaque to the model. */
    val address: String,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    @ColumnName("default_task_type_id")
    @Contextual
    val defaultTaskTypeId: UUID? = null,
    @ColumnName("default_priority_id")
    @Contextual
    val defaultPriorityId: UUID? = null,
    val enabled: Boolean = true,
    @ColumnName("hmac_secret")
    val hmacSecret: String? = null,
    val version: Long = 0,
)

/**
 * R30 — per-message audit row. Stores the raw MIME blob (encrypted
 * at rest in production; Phase 15 ships the plumbing without the
 * KMS wrapper) plus the parsed thread headers and the outcome.
 */
@Serializable
data class InboundEmail(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("inbox_id")
    @Contextual
    val inboxId: UUID,
    @ColumnName("message_id")
    val messageId: String?,
    @ColumnName("in_reply_to")
    val inReplyTo: String? = null,
    @ColumnName("from_address")
    val fromAddress: String,
    @ColumnName("subject")
    val subject: String? = null,
    val outcome: InboundEmailOutcome,
    @ColumnName("task_id")
    @Contextual
    val taskId: UUID? = null,
    @ColumnName("comment_id")
    val commentId: Long? = null,
    @ColumnName("error_message")
    val errorMessage: String? = null,
    @ColumnName("received_at")
    @Contextual
    val receivedAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("raw_mime")
    val rawMime: String? = null,
)

/**
 * R30 — outbound Message-ID registry. The notification dispatcher
 * stamps a stable Message-ID on every task / comment notification
 * email; the inbound processor matches `In-Reply-To` against this
 * registry to thread replies onto the right task or comment.
 */
@Serializable
data class OutboundMessageId(
    @ColumnName("message_id")
    val messageId: String,
    @ColumnName("task_id")
    @Contextual
    val taskId: UUID,
    @ColumnName("comment_id")
    val commentId: Long? = null,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)
