package bosca.bml.message.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MainConfigurationTest {

    @Test
    fun `graphql endpoint is required and blank is rejected`() {
        for (value in listOf<String?>(null, "", "  ")) {
            val failure = assertFailsWith<IllegalStateException> {
                requiredGraphqlEndpoint { value }
            }
            assertTrue("BML_GRAPHQL_ENDPOINT is required" in failure.message.orEmpty())
        }
    }

    @Test
    fun `configured graphql endpoint is returned unchanged`() {
        assertEquals(
            "http://bosca-server:8080/graphql",
            requiredGraphqlEndpoint { "http://bosca-server:8080/graphql" },
        )
    }
}
