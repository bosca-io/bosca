package bosca.server.graphql

import bosca.server.graphql.controllers.Mutation
import bosca.server.graphql.controllers.Query
import bosca.server.graphql.controllers.Subscription
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaTest {

    @Test
    fun `schema returns correct root objects`() {
        assertEquals(Query, Schema.query)
        assertEquals(Mutation, Schema.mutation)
        assertEquals(Subscription, Schema.subscription)
    }
}
