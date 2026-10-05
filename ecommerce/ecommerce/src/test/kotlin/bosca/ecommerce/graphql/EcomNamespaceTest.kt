package bosca.ecommerce.graphql

import kotlin.test.Test
import kotlin.test.assertNotNull

/** The GraphQL root namespace markers (`Query.ecom` -> Ecom, `Mutation.ecom` -> EcomMutation). */
class EcomNamespaceTest {

    @Test
    fun `the root namespace markers are referenceable`() {
        assertNotNull(Ecom)
        assertNotNull(EcomMutation)
    }
}
