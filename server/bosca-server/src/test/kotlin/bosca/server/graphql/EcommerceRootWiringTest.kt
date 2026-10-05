package bosca.server.graphql

import bosca.graphql.server.RuntimeWiringBuilder
import bosca.server.graphql.controllers.MutationController
import bosca.server.graphql.controllers.QueryController
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EcommerceRootWiringTest {

    @Test
    fun `disabled ecommerce does not register root resolvers`() {
        val wiring = RuntimeWiringBuilder()
            .apply { registerEcommerceRootFields(enabled = false) }
            .build()

        assertNull(wiring.fieldResolvers["Query"]?.get("ecom"))
        assertNull(wiring.fieldResolvers["Mutation"]?.get("ecom"))
    }

    @Test
    fun `enabled ecommerce registers root resolvers`() {
        val wiring = RuntimeWiringBuilder()
            .apply { registerEcommerceRootFields(enabled = true) }
            .build()

        assertNotNull(wiring.fieldResolvers["Query"]?.get("ecom"))
        assertNotNull(wiring.fieldResolvers["Mutation"]?.get("ecom"))
    }

    @Test
    fun `always loaded server controllers omit feature gated ecommerce roots`() {
        assertFalse(QueryController::class.java.declaredMethods.any { it.name == "ecom" })
        assertFalse(MutationController::class.java.declaredMethods.any { it.name == "ecom" })
    }
}
