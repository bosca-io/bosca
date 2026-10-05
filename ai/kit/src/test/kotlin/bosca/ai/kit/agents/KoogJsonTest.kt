package bosca.ai.kit.agents

import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.ToolCalls
import ai.koog.prompt.message.Message
import bosca.ai.kit.agents.chat.ChatRequest
import bosca.ai.kit.agents.chat.ChatResponse
import bosca.ai.kit.agents.graphql.GraphQLRequest
import bosca.ai.kit.agents.graphql.GraphQLResponse
import bosca.ai.kit.agents.graphql.GraphQLSummary
import bosca.ai.kit.agents.routing.RouteRequest
import bosca.ai.kit.agents.routing.RouteResponse
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertNotNull

class KoogJsonTest {

    /**
     * The native image resolves checkpoint root types through the module's contextual registrations
     * (the compiled-serializer lookup is reflective and returns null there), so losing one of these —
     * e.g. via the `+` module merge — silently disables checkpointing for that node. The JVM compiled
     * lookup masks such a loss everywhere else, hence this explicit guard.
     */
    @Test
    fun `checkpoint root types stay contextually registered for the native image`() {
        val module = koogJson(Json { ignoreUnknownKeys = true }).serializersModule

        val checkpointRoots: List<KClass<*>> = listOf(
            Message.Assistant::class,
            ToolCalls::class,
            ReceivedToolResults::class,
            KitState::class,
            KitRequest::class,
            KitResponse::class,
            ChatRequest::class,
            ChatResponse::class,
            GraphQLRequest::class,
            GraphQLResponse::class,
            GraphQLSummary::class,
            RouteRequest::class,
            RouteResponse::class,
        )

        checkpointRoots.forEach { klass ->
            assertNotNull(module.getContextual(klass), "No contextual serializer registered for ${klass.simpleName}")
        }
    }
}
