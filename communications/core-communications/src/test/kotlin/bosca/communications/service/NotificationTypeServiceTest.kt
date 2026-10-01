package bosca.communications.service

import bosca.communications.model.NotificationType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationTypeServiceTest {

    @Test
    fun `hidden is required and forwarded`() = runBlocking {
        val implementation = RecordingNotificationTypeService()
        val service: NotificationTypeService = implementation

        val result = service.set(
            key = "internal-updates",
            name = "Internal updates",
            description = "Internal messages",
            optional = true,
            defaultEmailEnabled = false,
            defaultPushEnabled = true,
            displayOrder = 20,
            hidden = true,
        )
        service.shutdown()

        assertEquals("internal-updates", implementation.key)
        assertEquals("Internal updates", implementation.name)
        assertEquals("Internal messages", implementation.description)
        assertEquals(true, implementation.optional)
        assertFalse(implementation.defaultEmailEnabled)
        assertTrue(implementation.defaultPushEnabled)
        assertEquals(20, implementation.displayOrder)
        assertTrue(implementation.hidden)
        assertTrue(result.hidden)
    }

    private class RecordingNotificationTypeService : NotificationTypeService {
        lateinit var key: String
        lateinit var name: String
        var description: String? = null
        var optional: Boolean = false
        var defaultEmailEnabled: Boolean = true
        var defaultPushEnabled: Boolean = true
        var displayOrder: Int = 0
        var hidden: Boolean = false

        override suspend fun list(): List<NotificationType> = emptyList()

        override suspend fun get(key: String): NotificationType? = null

        override suspend fun set(
            key: String,
            name: String,
            description: String?,
            optional: Boolean,
            defaultEmailEnabled: Boolean,
            defaultPushEnabled: Boolean,
            displayOrder: Int,
            hidden: Boolean,
        ): NotificationType {
            this.key = key
            this.name = name
            this.description = description
            this.optional = optional
            this.defaultEmailEnabled = defaultEmailEnabled
            this.defaultPushEnabled = defaultPushEnabled
            this.displayOrder = displayOrder
            this.hidden = hidden
            return NotificationType(
                key = key,
                name = name,
                description = description,
                optional = optional,
                defaultEmailEnabled = defaultEmailEnabled,
                defaultPushEnabled = defaultPushEnabled,
                displayOrder = displayOrder,
                hidden = hidden,
            )
        }

        override suspend fun delete(key: String): Boolean = false
    }
}
