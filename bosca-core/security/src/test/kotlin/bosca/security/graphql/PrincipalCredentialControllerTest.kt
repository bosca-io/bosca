package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.security.model.CredentialType
import bosca.security.model.PrincipalCredentialAndType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PrincipalCredentialControllerTest {

    private val controller = PrincipalCredentialController()

    @Test
    fun `PrincipalCredentialController implements GraphQLController`() {
        assertIs<GraphQLController<PrincipalCredentialAndType>>(controller)
    }

    @Test
    fun `identifier returns credential identifier`() {
        val credential = PrincipalCredentialAndType(identifier = "user@example.com", type = CredentialType.PASSWORD)
        assertEquals("user@example.com", controller.identifier(credential))
    }

    @Test
    fun `type returns credential type`() {
        val credential = PrincipalCredentialAndType(identifier = "user@example.com", type = CredentialType.PASSWORD)
        assertEquals(CredentialType.PASSWORD, controller.type(credential))
    }

    @Test
    fun `type returns OAUTH2 credential type`() {
        val credential = PrincipalCredentialAndType(identifier = "google-sub-id", type = CredentialType.OAUTH2)
        assertEquals(CredentialType.OAUTH2, controller.type(credential))
    }

    @Test
    fun `originator fields are exposed`() {
        val credential = PrincipalCredentialAndType(
            identifier = "google-sub-id",
            type = CredentialType.OAUTH2,
            originator = "signup",
            lastOriginator = "studio",
        )

        assertEquals("signup", controller.originator(credential))
        assertEquals("studio", controller.lastOriginator(credential))
    }
}
