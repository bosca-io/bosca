package bosca.git.graphql

import bosca.git.model.Webhook
import bosca.git.model.WebhookDelivery
import bosca.git.model.WebhookEvent
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Resolves all fields on [GitWebhook] representing a configured webhook
 * endpoint for a repository.
 */
@TypeController(type = "GitWebhook")
class GitWebhookController : GraphQLController<Webhook> {

    @Field
    fun id(source: Webhook): UUID = source.id

    @Field
    fun repositoryId(source: Webhook): UUID = source.repositoryId

    @Field
    fun url(source: Webhook): String = source.url

    @Field
    fun events(source: Webhook): List<WebhookEvent> = source.events

    @Field
    fun active(source: Webhook): Boolean = source.active

    @Field
    fun created(source: Webhook): OffsetDateTime = source.created
}

/**
 * Resolves all fields on [GitWebhookDelivery] representing a single delivery
 * attempt for a webhook event.
 */
@TypeController(type = "GitWebhookDelivery")
class GitWebhookDeliveryController : GraphQLController<WebhookDelivery> {

    @Field
    fun id(source: WebhookDelivery): UUID = source.id

    @Field
    fun webhookId(source: WebhookDelivery): UUID = source.webhookId

    @Field
    fun event(source: WebhookDelivery): String = source.event.name

    @Field
    fun responseStatus(source: WebhookDelivery): Int? = source.responseStatus

    @Field
    fun retryCount(source: WebhookDelivery): Int = source.retryCount

    @Field
    fun success(source: WebhookDelivery): Boolean = source.success

    @Field
    fun created(source: WebhookDelivery): OffsetDateTime = source.created
}
