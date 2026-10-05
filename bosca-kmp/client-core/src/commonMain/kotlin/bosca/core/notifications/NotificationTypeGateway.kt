package bosca.core.notifications

/** Backend operation used to retrieve the public notification-type catalog. */
interface NotificationTypeGateway {
    /** Returns the visible notification types in the server's presentation order. */
    suspend fun getNotificationTypes(): List<NotificationTypeDefinition>
}
