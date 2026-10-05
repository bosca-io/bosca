package bosca.communications.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.Batch
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.communications.model.DeliveryChannel
import bosca.communications.model.DeliveryStatus
import bosca.communications.model.DeliveryStatusType
import bosca.communications.model.BmlMessageTemplateRender
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.attribute.model.getAttributeString
import bosca.profile.profile.service.ProfileService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@TypeController(type = "DeliveryStatus")
class DeliveryStatusController(
    private val profileService: ProfileService,
) : GraphQLController<DeliveryStatus> {

    @Field fun messageId(status: DeliveryStatus): UUID = status.messageId
    @Field fun recipientId(status: DeliveryStatus): UUID = status.recipientId
    @Field
    suspend fun recipientName(batch: Batch<UUID, String>) {
        val profiles = profileService.getAllByIds(batch.keys.distinct()).associateBy { it.id }
        batch.keys.distinct().forEach { id ->
            profiles[id]?.name?.let { batch.setData(id, it) }
        }
    }

    @Field
    suspend fun recipientEmail(batch: Batch<UUID, String>) {
        val attributes = Batch<UUID, List<ProfileAttribute>>(batch.keys.distinct())
        profileService.addAttributesToBatch(attributes)
        batch.keys.distinct().forEach { id ->
            val email = attributes.getData(id)
                ?.getAttributeString("bosca.profiles.email", "email")
            email?.let { batch.setData(id, it) }
        }
    }

    @Field fun channel(status: DeliveryStatus): DeliveryChannel = status.channel
    @Field fun status(status: DeliveryStatus): DeliveryStatusType = status.status
    @Field fun attempts(status: DeliveryStatus): Int = status.attempts
    @Field fun lastAttemptAt(status: DeliveryStatus): OffsetDateTime? = status.lastAttemptAt
    @Field fun deliveredAt(status: DeliveryStatus): OffsetDateTime? = status.deliveredAt
    @Field fun bouncedAt(status: DeliveryStatus): OffsetDateTime? = status.bouncedAt
    @Field fun openedAt(status: DeliveryStatus): OffsetDateTime? = status.openedAt
    @Field fun clickedAt(status: DeliveryStatus): OffsetDateTime? = status.clickedAt
    @Field fun errorCode(status: DeliveryStatus): String? = status.errorCode
    @Field fun errorMessage(status: DeliveryStatus): String? = status.errorMessage
    @Field fun bmlTemplate(status: DeliveryStatus): BmlMessageTemplateRender? = status.bmlTemplate
    @Field fun createdAt(status: DeliveryStatus): OffsetDateTime = status.createdAt
    @Field fun updatedAt(status: DeliveryStatus): OffsetDateTime = status.updatedAt
}
