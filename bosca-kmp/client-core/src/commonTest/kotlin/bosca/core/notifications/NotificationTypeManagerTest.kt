package bosca.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

class NotificationTypeManagerTest {
    @Test
    fun synchronizePublishesServerTypesAndReconcilesNativeChannels() = runTest {
        val types = listOf(
            NotificationTypeDefinition("chat", "Chat messages", "New conversations and replies"),
            NotificationTypeDefinition("content", "Content", "New content available"),
        )
        val provider = RecordingTokenProvider()
        val manager = NotificationTypeManagerImpl(
            gateway = object : NotificationTypeGateway {
                override suspend fun getNotificationTypes(): List<NotificationTypeDefinition> = types
            },
            tokenProvider = provider,
        )

        manager.synchronize()

        assertEquals(types, manager.types.value)
        assertEquals(types, provider.synchronizedTypes)
    }

    private class RecordingTokenProvider : PushTokenProvider {
        override val platform: PushDevicePlatform = PushDevicePlatform.DESKTOP
        override val supported: Boolean = false
        override val tokenChanges: Flow<String> = emptyFlow()
        override val messages: Flow<PushMessage> = emptyFlow()
        var synchronizedTypes: List<NotificationTypeDefinition> = emptyList()

        override suspend fun currentToken(): String? = null
        override suspend fun deleteToken() = Unit

        override suspend fun synchronizeNotificationTypes(types: List<NotificationTypeDefinition>) {
            synchronizedTypes = types
        }
    }
}
