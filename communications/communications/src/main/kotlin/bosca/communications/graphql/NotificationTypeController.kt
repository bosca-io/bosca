package bosca.communications.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.communications.model.NotificationType
import bosca.serialization.OffsetDateTime

@TypeController(type = "NotificationType")
class NotificationTypeController : GraphQLController<NotificationType> {

    @Field fun key(type: NotificationType): String = type.key
    @Field fun name(type: NotificationType): String = type.name
    @Field fun description(type: NotificationType): String? = type.description
    @Field fun optional(type: NotificationType): Boolean = type.optional
    @Field fun system(type: NotificationType): Boolean = type.system
    @Field fun defaultEmailEnabled(type: NotificationType): Boolean = type.defaultEmailEnabled
    @Field fun defaultPushEnabled(type: NotificationType): Boolean = type.defaultPushEnabled
    @Field fun hidden(type: NotificationType): Boolean = type.hidden
    @Field fun displayOrder(type: NotificationType): Int = type.displayOrder
    @Field fun updatedAt(type: NotificationType): OffsetDateTime = type.updatedAt
}
