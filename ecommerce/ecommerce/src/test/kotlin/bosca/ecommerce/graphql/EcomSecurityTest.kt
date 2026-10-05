package bosca.ecommerce.graphql

import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi

/**
 * acceptance (permissions): the `ecom.administrator` gate every non-Metadata admin mutation
 * uses (`verifyEcomAdmin`) rejects non-admins and anonymous callers, and admits members of the group.
 * A non-admin failing this gate is exactly "a non-admin cannot mutate catalog data".
 */
@OptIn(ExperimentalUuidApi::class)
class EcomSecurityTest {

    private val groups = GroupEvaluator(mockk<SecurityService>(relaxed = true))

    private fun auth(vararg memberGroups: String): AuthenticationContext =
        ImpersonatedAuthenticationContext(
            Principal(id = UUID.random()),
            memberGroups.map { Group(name = it, description = "", type = GroupType.SYSTEM) },
        )

    @Test
    fun `a non-admin cannot pass the ecom admin gate`() {
        assertFailsWith<SecurityException> { groups.verifyEcomAdmin(auth("some.other.group")) }
    }

    @Test
    fun `an unauthenticated caller cannot pass the ecom admin gate`() {
        assertFailsWith<SecurityException> { groups.verifyEcomAdmin(null) }
    }

    @Test
    fun `an ecom administrator passes the gate`() {
        groups.verifyEcomAdmin(auth(ECOM_ADMINISTRATOR_GROUP))
    }
}
