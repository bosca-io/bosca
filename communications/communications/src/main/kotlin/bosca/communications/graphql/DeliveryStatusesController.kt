package bosca.communications.graphql

import bosca.communications.model.DeliveryStatus
import bosca.communications.model.DeliveryStatuses
import bosca.communications.model.RecipientDeliveryStatus
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController(type = "DeliveryStatuses")
class DeliveryStatusesController : GraphQLController<DeliveryStatuses> {

    @Field
    fun statuses(deliveryStatuses: DeliveryStatuses): List<RecipientDeliveryStatus> = deliveryStatuses.statuses

    @Field
    fun total(deliveryStatuses: DeliveryStatuses): Long = deliveryStatuses.total
}

@TypeController(type = "RecipientDeliveryStatus")
class RecipientDeliveryStatusController : GraphQLController<RecipientDeliveryStatus> {

    @Field
    fun delivery(status: RecipientDeliveryStatus): DeliveryStatus = status.delivery

    @Field
    fun recipientName(status: RecipientDeliveryStatus): String? = status.recipientName

    @Field
    fun recipientEmail(status: RecipientDeliveryStatus): String? = status.recipientEmail
}
