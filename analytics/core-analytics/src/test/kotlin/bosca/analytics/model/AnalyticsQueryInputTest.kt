package bosca.analytics.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class AnalyticsQueryInputTest {

    @Test
    fun `AnalyticsQueryInput id defaults to NIL`() {
        val input = AnalyticsQueryInput(key = "k", name = "n", description = "d", query = "SELECT 1")
        assertEquals(Uuid.NIL, input.id)
    }

    @Test
    fun `AnalyticsQueryInput parameters defaults to empty list`() {
        val input = AnalyticsQueryInput(key = "k", name = "n", description = "d", query = "SELECT 1")
        assertEquals(emptyList(), input.parameters)
    }

    @Test
    fun `AnalyticsQueryInput configuration defaults to null`() {
        val input = AnalyticsQueryInput(key = "k", name = "n", description = "d", query = "SELECT 1")
        assertNull(input.configuration)
    }

    @Test
    fun `AnalyticsQueryInput stores all properties`() {
        val input = AnalyticsQueryInput(
            key = "daily-users", name = "Daily Users",
            description = "Count daily active users",
            query = "SELECT count(*) FROM users WHERE active_at > :date"
        )
        assertEquals("daily-users", input.key)
        assertEquals("Daily Users", input.name)
        assertEquals("Count daily active users", input.description)
    }
}
