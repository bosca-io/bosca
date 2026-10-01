package bosca.communications.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.communications.model.DeliveryChannel
import bosca.communications.model.DeliveryEvent
import bosca.communications.model.DeliveryStatusType
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@TypeController(type = "DeliveryEvent")
class DeliveryEventController : GraphQLController<DeliveryEvent> {

    @Field fun messageId(event: DeliveryEvent): UUID = event.messageId
    @Field fun recipientId(event: DeliveryEvent): UUID = event.recipientId
    @Field fun channel(event: DeliveryEvent): DeliveryChannel = event.channel
    @Field fun status(event: DeliveryEvent): DeliveryStatusType = event.status
    @Field fun providerEvent(event: DeliveryEvent): String? = event.providerEvent
    @Field fun errorCode(event: DeliveryEvent): String? = event.errorCode
    @Field fun errorMessage(event: DeliveryEvent): String? = event.errorMessage
    @Field fun createdAt(event: DeliveryEvent): OffsetDateTime = event.createdAt
}
