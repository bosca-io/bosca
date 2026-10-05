package bosca.community.graphql

import bosca.community.model.CommunityGroupSignupToken
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class CommunityGroupSignupTokenControllerTest {

    private val controller = CommunityGroupSignupTokenController()

    private val groupId = UUID.random()
    private val created = OffsetDateTime.now()
    private val expires = OffsetDateTime.now().plusDays(30)

    private val signupToken = CommunityGroupSignupToken(
        token = "abc-123-xyz",
        groupId = groupId,
        created = created,
        expires = expires
    )

    @Test
    fun `token returns the signup token string`() {
        assertEquals("abc-123-xyz", controller.token(signupToken))
    }

    @Test
    fun `groupId returns the associated group ID`() {
        assertEquals(groupId, controller.groupId(signupToken))
    }

    @Test
    fun `created returns the creation timestamp`() {
        assertEquals(created, controller.created(signupToken))
    }

    @Test
    fun `expires returns the expiration timestamp`() {
        assertEquals(expires, controller.expires(signupToken))
    }
}
