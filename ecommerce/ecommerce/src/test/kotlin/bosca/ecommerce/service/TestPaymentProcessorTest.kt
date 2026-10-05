package bosca.ecommerce.service

import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.SavedPaymentMethod
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**the always-approve test payment processor (save-then-recharge flow). */
@OptIn(ExperimentalUuidApi::class)
class TestPaymentProcessorTest {

    private val processor = TestPaymentProcessor()

    private fun provider() = PaymentProvider(id = UUID.random(), companyId = UUID.random(), name = "Test", providerKey = "test")

    @Test
    fun `key is test`() {
        assertEquals("test", processor.key)
    }

    @Test
    fun `submit approves and does not vault when save is false`() = runTest {
        // save = false -> the `if (save) ... else null` else branch: no saved method.
        val result = processor.submit(provider(), Money.of("10.00"), "USD", token = "tok", creditCard = null, save = false)
        assertTrue(result.complete)
        assertTrue(result.confirmed)
        assertEquals("approved", result.status)
        assertNull(result.saved)
    }

    @Test
    fun `submit vaults a reusable method derived from the token when save is true`() = runTest {
        // save = true -> the if branch returns a SavedPaymentMethod whose token derives from the input token.
        val result = processor.submit(provider(), Money.of("10.00"), "USD", token = "tok", creditCard = null, save = true)
        val saved = assertNotNull(result.saved)
        assertEquals("test", saved.providerKey)
        assertEquals("save-tok", saved.token)
    }

    @Test
    fun `submit vaults a generated token when saving with no token`() = runTest {
        // save = true with a null token -> the `token ?: Uuid.random()` falls through to a random suffix.
        val result = processor.submit(provider(), Money.of("10.00"), "USD", token = null, creditCard = null, save = true)
        val saved = assertNotNull(result.saved)
        assertTrue(saved.token.startsWith("save-"))
        assertEquals("test", saved.providerKey)
    }

    @Test
    fun `refund always confirms a refund transaction`() = runTest {
        val result = processor.refund(provider(), originalTransactionId = "test-1", amount = Money.of("5.00"), currency = "USD")
        assertTrue(result.complete)
        assertTrue(result.confirmed)
        assertEquals("refunded", result.status)
        assertTrue(result.transactionId!!.startsWith("test-refund-"))
    }

    @Test
    fun `charge approves a saved-method recharge`() = runTest {
        val result = processor.charge(provider(), SavedPaymentMethod(providerKey = "test", token = "save-tok"), Money.of("9.00"), "USD")
        assertTrue(result.complete)
        assertTrue(result.confirmed)
        assertEquals("approved", result.status)
        assertTrue(result.transactionId!!.startsWith("test-charge-"))
    }
}
