package bosca.ai.kit.agents

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessageMetaInfo
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KoogJsonTest {

    /**
     * Koog resolves its sealed message interfaces through an open polymorphic serializer, which consults
     * the module's polymorphic registrations rather than the sealed serializer. Losing one — e.g. via the
     * `+` module merge — breaks decoding of stored conversation messages.
     */
    @Test
    fun `Koog message hierarchies stay polymorphically registered`() {
        val module = koogJson(Json { ignoreUnknownKeys = true }).serializersModule

        assertNotNull(module.getPolymorphic(Message::class, Message.System("x", RequestMetaInfo.Empty)), "Message.System")
        assertNotNull(module.getPolymorphic(Message::class, Message.User("x", RequestMetaInfo.Empty)), "Message.User")
        assertNotNull(module.getPolymorphic(Message::class, Message.Assistant("x", ResponseMetaInfo.Empty)), "Message.Assistant")
        assertNotNull(module.getPolymorphic(MessagePart::class, MessagePart.Text("x")), "MessagePart.Text")
        assertNotNull(module.getPolymorphic(MessageMetaInfo::class, RequestMetaInfo.Empty), "RequestMetaInfo")
        assertNotNull(module.getPolymorphic(MessageMetaInfo::class, ResponseMetaInfo.Empty), "ResponseMetaInfo")
    }

    @Test
    fun `a stored Koog message round-trips through the Kit Json`() {
        val json = koogJson(Json { ignoreUnknownKeys = true })
        val message = Message.User("Hi Kit", RequestMetaInfo.Empty)

        val decoded = json.decodeFromString(Message.serializer(), json.encodeToString(Message.serializer(), message))

        assertEquals(message, decoded)
    }
}
