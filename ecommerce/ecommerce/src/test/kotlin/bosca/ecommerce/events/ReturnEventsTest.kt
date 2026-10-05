package bosca.ecommerce.events

import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/** The return events carry their identity (returnId) and behave as value types (equality/copy + JSON round-trip). */
@OptIn(ExperimentalUuidApi::class)
class ReturnEventsTest {

    private val returnId = UUID.random()
    private val storeId = UUID.random()
    private val cartId = UUID.random()
    private val companyId = UUID.random()

    // The events' UUID fields are @Contextual, so the module supplies the platform UUID serializer.
    private val json = Json { serializersModule = SerializersModule { contextual(Uuid::class, UUIDSerializer()) } }

    @Test
    fun `ReturnRequested is a value type keyed on the return id`() {
        val event = ReturnRequested(returnId, storeId, cartId, companyId)
        assertEquals(ReturnRequested(returnId, storeId, cartId, companyId), event)
        assertEquals(ReturnRequested(returnId, storeId, cartId, companyId).hashCode(), event.hashCode())
        assertNotEquals(event, event.copy(returnId = UUID.random()))
        assertNotEquals(event, event.copy(cartId = UUID.random()))
        assertEquals(returnId, event.identityKey())
    }

    @Test
    fun `ReturnRefunded is a value type keyed on the return id`() {
        val event = ReturnRefunded(returnId, storeId, cartId, companyId)
        assertEquals(ReturnRefunded(returnId, storeId, cartId, companyId), event)
        assertEquals(ReturnRefunded(returnId, storeId, cartId, companyId).hashCode(), event.hashCode())
        assertNotEquals(event, event.copy(storeId = UUID.random()))
        assertNotEquals(event, event.copy(companyId = UUID.random()))
        assertEquals(returnId, event.identityKey())
    }

    @Test
    fun `events round-trip through json (exercises the generated serializers)`() {
        val req = ReturnRequested(returnId, storeId, cartId, companyId)
        assertEquals(req, json.decodeFromString(ReturnRequested.serializer(), json.encodeToString(ReturnRequested.serializer(), req)))
        val refunded = ReturnRefunded(returnId, storeId, cartId, companyId)
        assertEquals(refunded, json.decodeFromString(ReturnRefunded.serializer(), json.encodeToString(ReturnRefunded.serializer(), refunded)))
    }

    // Both events have only required fields, so decoding "{}" must throw from the generated
    // deserializer's missing-field branch (matches the pattern in EcomEventsSerializationTest).
    @Test
    fun `events reject json that omits required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(ReturnRequested.serializer(), "{}") }
        assertFailsWith<SerializationException> { json.decodeFromString(ReturnRefunded.serializer(), "{}") }
    }
}
