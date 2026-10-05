package bosca.graphql.persistedqueries

import kotlin.test.Test
import kotlin.test.assertNotNull

class PersistedQueriesTest {

    @Test
    fun persistedQueriesIsObject() {
        assertNotNull(PersistedQueries)
    }

    @Test
    fun persistedQueriesMutationIsObject() {
        assertNotNull(PersistedQueriesMutation)
    }
}
