package bosca.server.graphql

import kotlin.test.Test

class BoscaGraphQLServiceTest {

    @Test
    fun `constructor creates service with introspection disabled`() {
        val service = BoscaGraphQLService(introspectionEnabled = false)
        // Service created without exception
    }

    @Test
    fun `constructor creates service with introspection enabled`() {
        val service = BoscaGraphQLService(introspectionEnabled = true)
        // Service created without exception
    }
}
