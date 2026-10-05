package bosca.communications.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.communications.model.DeliveryChannel
import bosca.communications.model.NotificationPreference
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@TypeController(type = "NotificationPreference")
class NotificationPreferenceController : GraphQLController<NotificationPreference> {

    @Field fun profileId(pref: NotificationPreference): UUID = pref.profileId
    @Field fun channel(pref: NotificationPreference): DeliveryChannel = pref.channel
    @Field fun type(pref: NotificationPreference): String = pref.type
    @Field fun optedOut(pref: NotificationPreference): Boolean = pref.optedOut
    @Field fun updatedAt(pref: NotificationPreference): OffsetDateTime = pref.updatedAt
}
