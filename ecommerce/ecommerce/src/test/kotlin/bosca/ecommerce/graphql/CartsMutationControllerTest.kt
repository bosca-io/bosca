package bosca.ecommerce.graphql

import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartInput
import bosca.ecommerce.model.CartStatus
import bosca.ecommerce.model.CartStatusFlag
import bosca.ecommerce.service.CartService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Cart creation gates through CartAccessEvaluator before delegating; cart(id) builds the id-scoped namespace. */
@OptIn(ExperimentalUuidApi::class)
class CartsMutationControllerTest {

    private val cartService = mockk<CartService>()
    private val cartAccess = mockk<CartAccessEvaluator>()
    private val auth = mockk<AuthenticationContext>()
    private val controller = CartsMutationController(cartService, cartAccess)

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun cart(id: UUID) = Cart(
        id = id, companyId = UUID.random(), storeId = UUID.random(),
        status = CartStatus.of(CartStatusFlag.OPEN), expires = OffsetDateTime.now().plusSeconds(3600),
    )

    @Test
    fun `create gates access then delegates`() = runTest {
        val storeId = UUID.random()
        val input = CartInput(storeId = storeId)
        val created = cart(UUID.random())
        coEvery { cartAccess.verifyCreate(auth, input) } just Runs
        coEvery { cartService.create(input, null) } returns created

        val result = controller.create(auth, input)

        assertEquals(created, result)
        coVerify(exactly = 1) { cartAccess.verifyCreate(auth, input) }
        coVerify(exactly = 1) { cartService.create(input, null) }
    }

    @Test
    fun `create passes the resolved principal id to the service`() = runTest {
        val storeId = UUID.random()
        val principalId = UUID.random()
        val input = CartInput(storeId = storeId)
        val created = cart(UUID.random())
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { cartAccess.verifyCreate(auth, input) } just Runs
        coEvery { cartService.create(input, principalId) } returns created

        assertEquals(created, controller.create(auth, input))
        coVerify(exactly = 1) { cartService.create(input, principalId) }
    }

    @Test
    fun `cart builds an id-scoped mutation namespace`() {
        val id = UUID.random()
        assertEquals(CartMutation(id), controller.cart(id))
    }
}
