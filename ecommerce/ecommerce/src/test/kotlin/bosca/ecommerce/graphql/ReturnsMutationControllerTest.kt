package bosca.ecommerce.graphql

import bosca.ecommerce.model.RefundTender
import bosca.ecommerce.model.Return
import bosca.ecommerce.model.ReturnInput
import bosca.ecommerce.model.ReturnLineInput
import bosca.ecommerce.service.ReturnService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Return/RMA mutations: each gates on the ecom-admin group, then delegates to the service with the principal. */
@OptIn(ExperimentalUuidApi::class)
class ReturnsMutationControllerTest {

    private val returnService = mockk<ReturnService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = ReturnsMutationController(returnService, groups)

    private val id = UUID.random()
    private val principalId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun gated() = verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }

    @Test
    fun `request gates and delegates with the authenticated principal`() = runTest {
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val input = ReturnInput(cartId = UUID.random(), tender = RefundTender.ORIGINAL, lines = listOf(ReturnLineInput(UUID.random(), 1)))
        val ret = mockk<Return>()
        coEvery { returnService.request(input, principalId) } returns ret
        assertSame(ret, controller.request(auth, input))
        gated()
        coVerify(exactly = 1) { returnService.request(input, principalId) }
    }

    @Test
    fun `approve gates and delegates`() = runTest {
        val ret = mockk<Return>()
        coEvery { returnService.approve(id, null) } returns ret
        assertSame(ret, controller.approve(auth, id))
        gated()
    }

    @Test
    fun `reject gates and delegates with the reason`() = runTest {
        val ret = mockk<Return>()
        coEvery { returnService.reject(id, "nope", null) } returns ret
        assertSame(ret, controller.reject(auth, id, "nope"))
        gated()
    }

    @Test
    fun `receive gates and delegates`() = runTest {
        val ret = mockk<Return>()
        coEvery { returnService.receive(id, null) } returns ret
        assertSame(ret, controller.receive(auth, id))
        gated()
    }

    @Test
    fun `refund gates and delegates`() = runTest {
        val ret = mockk<Return>()
        coEvery { returnService.refund(id, null) } returns ret
        assertSame(ret, controller.refund(auth, id))
        gated()
    }

    @Test
    fun `request delegates with a null principal`() = runTest {
        val input = ReturnInput(cartId = UUID.random(), tender = RefundTender.ORIGINAL, lines = listOf(ReturnLineInput(UUID.random(), 1)))
        coEvery { returnService.request(input, null) } returns mockk()
        controller.request(auth, input) // auth.principal() is null by default
        coVerify(exactly = 1) { returnService.request(input, null) }
    }

    @Test
    fun `the id-scoped mutations delegate with the authenticated principal id`() = runTest {
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { returnService.approve(id, principalId) } returns mockk()
        coEvery { returnService.reject(id, null, principalId) } returns mockk()
        coEvery { returnService.receive(id, principalId) } returns mockk()
        coEvery { returnService.refund(id, principalId) } returns mockk()
        controller.approve(auth, id)
        controller.reject(auth, id)
        controller.receive(auth, id)
        controller.refund(auth, id)
        coVerify(exactly = 1) { returnService.approve(id, principalId) }
        coVerify(exactly = 1) { returnService.reject(id, null, principalId) }
        coVerify(exactly = 1) { returnService.receive(id, principalId) }
        coVerify(exactly = 1) { returnService.refund(id, principalId) }
    }
}
