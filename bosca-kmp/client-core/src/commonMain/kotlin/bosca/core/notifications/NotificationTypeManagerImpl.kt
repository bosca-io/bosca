package bosca.core.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Default notification-type catalog implementation. */
class NotificationTypeManagerImpl(
    private val gateway: NotificationTypeGateway,
    private val tokenProvider: PushTokenProvider,
) : NotificationTypeManager {
    private val mutableTypes = MutableStateFlow<List<NotificationTypeDefinition>>(emptyList())

    override val types: StateFlow<List<NotificationTypeDefinition>> = mutableTypes.asStateFlow()

    override suspend fun synchronize() {
        val currentTypes = gateway.getNotificationTypes()
        tokenProvider.synchronizeNotificationTypes(currentTypes)
        mutableTypes.value = currentTypes
    }
}
