package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.Webhook
import bosca.git.model.WebhookDelivery
import bosca.serialization.UUID

/**
 * Database access layer for webhooks and their delivery history.
 */
@Repository
interface WebhookRepository {

    @Query("select * from git.webhooks where repository_id = :repositoryId order by created")
    suspend fun findByRepository(repositoryId: UUID): List<Webhook>

    @Query("select * from git.webhooks where repository_id = :repositoryId and active = true")
    suspend fun findActiveByRepository(repositoryId: UUID): List<Webhook>

    @Query("select * from git.webhooks where id = :id")
    suspend fun findById(id: UUID): Webhook?

    @Query("""
        insert into git.webhooks (repository_id, url, secret, events, active)
        values (:repositoryId, :url, :secret, :events, :active)
        returning *
    """)
    suspend fun create(webhook: Webhook): Webhook

    @Query("""
        update git.webhooks set url = :url, events = :events, active = :active where id = :id returning *
    """)
    suspend fun update(webhook: Webhook): Webhook?

    @Query("delete from git.webhooks where id = :id")
    suspend fun delete(id: UUID)

    @Query("""
        select * from git.webhook_deliveries where webhook_id = :webhookId
        order by created desc limit :limit offset :offset
    """)
    suspend fun findDeliveries(webhookId: UUID, offset: Long, limit: Int): List<WebhookDelivery>

    @Query("""
        insert into git.webhook_deliveries (webhook_id, event, payload, response_status, response_body, delivered_at, retry_count, success)
        values (:webhookId, :event, :payload, :responseStatus, :responseBody, :deliveredAt, :retryCount, :success)
        returning *
    """)
    suspend fun createDelivery(delivery: WebhookDelivery): WebhookDelivery

    @Query("""
        update git.webhook_deliveries
        set response_status = :responseStatus, response_body = :responseBody, delivered_at = :deliveredAt,
            retry_count = :retryCount, success = :success
        where id = :id returning *
    """)
    suspend fun updateDelivery(delivery: WebhookDelivery): WebhookDelivery?

    @Query("select * from git.webhook_deliveries where id = :id")
    suspend fun findDeliveryById(id: UUID): WebhookDelivery?

    @Query("""
        update git.webhook_deliveries
        set response_status = :statusCode, response_body = :responseBody,
            delivered_at = now(), success = (:statusCode >= 200 and :statusCode < 300)
        where id = :id
    """)
    suspend fun recordDeliveryResult(id: UUID, statusCode: Int, responseBody: String?)
}
