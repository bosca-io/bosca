package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.emailin.EmailInbox
import bosca.workops.model.emailin.InboundEmail
import bosca.workops.model.emailin.OutboundMessageId

@Repository
interface EmailInboxRepository {

    @Query("select * from workops.email_inbox where id = :id")
    suspend fun getById(id: UUID): EmailInbox?

    @Query("select * from workops.email_inbox where enabled = true and address = :address")
    suspend fun getByAddress(address: String): EmailInbox?

    @Query("select * from workops.email_inbox where project_id = :projectId order by name")
    suspend fun listForProject(projectId: UUID): List<EmailInbox>

    @Query("select * from workops.email_inbox order by name")
    suspend fun listAll(): List<EmailInbox>

    @Query(
        """
        insert into workops.email_inbox
            (name, description, kind, address, project_id,
             default_task_type_id, default_priority_id, enabled, hmac_secret)
        values
            (:name, :description, :kind, :address, :projectId,
             :defaultTaskTypeId, :defaultPriorityId, :enabled, :hmacSecret)
        returning *
        """
    )
    suspend fun add(input: EmailInboxInsertParams): EmailInbox
}

data class EmailInboxInsertParams(
    val name: String,
    val description: String?,
    val kind: String,
    val address: String,
    val projectId: UUID,
    val defaultTaskTypeId: UUID?,
    val defaultPriorityId: UUID?,
    val enabled: Boolean,
    val hmacSecret: String?,
)

@Repository
interface InboundEmailRepository {

    @Query(
        """
        insert into workops.inbound_email
            (inbox_id, message_id, in_reply_to, from_address, subject,
             outcome, task_id, comment_id, error_message, raw_mime)
        values
            (:inboxId, :messageId, :inReplyTo, :fromAddress, :subject,
             :outcome, :taskId, :commentId, :errorMessage, :rawMime)
        returning *
        """
    )
    suspend fun add(input: InboundEmailInsertParams): InboundEmail

    @Query(
        """
        select * from workops.inbound_email
        where inbox_id = :inboxId
        order by received_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listForInbox(inboxId: UUID, offset: Long, limit: Int): List<InboundEmail>

    @Query("select * from workops.inbound_email where outcome like 'DLQ_%' order by received_at desc limit :limit")
    suspend fun listDeadLetters(limit: Int): List<InboundEmail>
}

data class InboundEmailInsertParams(
    val inboxId: UUID,
    val messageId: String?,
    val inReplyTo: String?,
    val fromAddress: String,
    val subject: String?,
    val outcome: String,
    val taskId: UUID?,
    val commentId: Long?,
    val errorMessage: String?,
    val rawMime: String?,
)

@Repository
interface OutboundMessageIdRepository {

    @Query("select * from workops.outbound_message_id where message_id = :messageId")
    suspend fun resolve(messageId: String): OutboundMessageId?

    @Query(
        """
        insert into workops.outbound_message_id (message_id, task_id, comment_id)
        values (:messageId, :taskId, :commentId)
        on conflict (message_id) do nothing
        returning *
        """
    )
    suspend fun add(messageId: String, taskId: UUID, commentId: Long?): OutboundMessageId?
}
