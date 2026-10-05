package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.security.model.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TokenControllerTest {

    private val controller = TokenController()

    private val token = Token(
        expiresAt = 1700000000,
        issuedAt = 1699900000,
        token = "jwt-token-string"
    )

    @Test
    fun `TokenController implements GraphQLController`() {
        assertIs<GraphQLController<Token>>(controller)
    }

    @Test
    fun `expiresAt returns token expiration`() {
        assertEquals(1700000000, controller.expiresAt(token))
    }

    @Test
    fun `issuedAt returns token issue time`() {
        assertEquals(1699900000, controller.issuedAt(token))
    }

    @Test
    fun `token returns token string`() {
        assertEquals("jwt-token-string", controller.token(token))
    }
}
