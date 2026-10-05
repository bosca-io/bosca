package bosca.communications.graphql

import bosca.communications.model.DeliveryChannel
import bosca.communications.model.NotificationPreferenceMapping
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController(type = "NotificationPreferenceMapping")
class NotificationPreferenceMappingController : GraphQLController<NotificationPreferenceMapping> {

    @Field fun type(mapping: NotificationPreferenceMapping): String = mapping.type
    @Field fun channel(mapping: NotificationPreferenceMapping): DeliveryChannel = mapping.channel
    @Field fun provider(mapping: NotificationPreferenceMapping): String = mapping.provider
    @Field fun externalId(mapping: NotificationPreferenceMapping): String = mapping.externalId
}
