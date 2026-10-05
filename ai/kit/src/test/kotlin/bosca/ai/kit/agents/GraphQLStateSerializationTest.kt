package bosca.ai.kit.agents

import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.typeToken
import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.chat.model.ChatMessagePartInput
import bosca.ai.kit.configuration.KitJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

class GraphQLStateSerializationTest {

    @Test
    fun `KitState holding a GraphQL response serializes through the planner serializer`() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val serializer = KitSerializer(KotlinxSerializer(koogJson(json)))

        val state = KitState(
            request = KitRequest(message = ChatMessageInput(role = "user", parts = listOf(ChatMessagePartInput(type = "text", text = "list my collections")))),
            route = KitRoute.GRAPHQL,
            responded = true,
            response = KitResponse.GraphQL(
                message = "You have 1 collection.",
                data = buildJsonObject { put("data", buildJsonObject { put("collections", JsonPrimitive("c1")) }) },
                type = "Collection",
                sdl = "type Collection { id: ID! name: String! }",
            ),
        )

        // This is exactly what the planner does to checkpoint its state; it must not throw.
        val element = serializer.encodeToJSONElement(state, typeToken<KitState>())
        println("ENCODED OK: $element")
    }
}
