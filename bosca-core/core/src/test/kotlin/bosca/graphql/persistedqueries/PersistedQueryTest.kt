package bosca.graphql.persistedqueries

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PersistedQueryTest {

    @Test
    fun `PersistedQuery stores all properties`() {
        val query = PersistedQuery(
            application = "web",
            query = "{ users { id } }",
            sha256 = "abc123"
        )
        assertEquals("web", query.application)
        assertEquals("{ users { id } }", query.query)
        assertEquals("abc123", query.sha256)
    }

    @Test
    fun `PersistedQuery application defaults to null`() {
        val query = PersistedQuery(query = "{ hello }", sha256 = "def456")
        assertNull(query.application)
    }
}
