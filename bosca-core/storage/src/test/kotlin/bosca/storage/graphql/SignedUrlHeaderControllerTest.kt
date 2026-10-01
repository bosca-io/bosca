package bosca.storage.graphql

import bosca.graphql.GraphQLController
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SignedUrlHeaderControllerTest {

    @Test
    fun `controller can be instantiated`() {
        val controller = SignedUrlHeaderController()
        assertNotNull(controller)
    }

    @Test
    fun `controller implements GraphQLController`() {
        val controller = SignedUrlHeaderController()
        assertIs<GraphQLController<*>>(controller)
    }
}
