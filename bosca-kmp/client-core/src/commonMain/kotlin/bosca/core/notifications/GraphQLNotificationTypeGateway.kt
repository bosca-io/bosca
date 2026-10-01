package bosca.core.notifications

import bosca.core.graphql.GetNotificationTypes
import bosca.graphql.client.GraphQLClient
import bosca.graphql.client.execute

/** Notification-type catalog backed by Bosca's typed GraphQL client. */
class GraphQLNotificationTypeGateway(
    private val client: GraphQLClient,
) : NotificationTypeGateway {
    override suspend fun getNotificationTypes(): List<NotificationTypeDefinition> =
        client.execute(GetNotificationTypes, Unit).communications.notificationTypes.map { type ->
            NotificationTypeDefinition(
                key = type.key,
                name = type.name,
                description = type.description,
            )
        }
}
