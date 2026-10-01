package bosca.communications.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.communications.model.NotificationSettings
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@TypeController(type = "NotificationSettings")
class NotificationSettingsController : GraphQLController<NotificationSettings> {

    @Field fun profileId(settings: NotificationSettings): UUID = settings.profileId
    @Field fun timeZone(settings: NotificationSettings): String? = settings.timeZone
    @Field fun dndStartLocal(settings: NotificationSettings): String? = settings.dndStartLocal
    @Field fun dndEndLocal(settings: NotificationSettings): String? = settings.dndEndLocal
    @Field fun updatedAt(settings: NotificationSettings): OffsetDateTime = settings.updatedAt
}
