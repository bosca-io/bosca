package bosca.analytics.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AnalyticsQueryTest {

    @Test
    fun `AnalyticsQuery id defaults to NIL`() {
        val query = AnalyticsQuery(
            key = "active-users",
            name = "Active Users",
            description = "Count of active users",
            query = "SELECT count(*) FROM users WHERE active = true"
        )
        assertEquals(Uuid.NIL, query.id)
        assertNull(query.configuration)
    }

    @Test
    fun `AnalyticsQuery permission flags`() {
        val query = AnalyticsQuery(
            key = "test",
            name = "Test",
            description = "Test query",
            query = "SELECT 1"
        )
        assertFalse(query.public)
        assertFalse(query.publicContent)
        assertFalse(query.publicList)
        assertFalse(query.publicSupplementary)
        assertTrue(query.isPublished)
        assertFalse(query.isAdvertised)
        assertFalse(query.isDeleted)
    }
}
