package bosca.ai.kit.agents

import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.ToolCalls
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessageMetaInfo
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import bosca.ai.kit.agents.analytics.AnalyticsRequest
import bosca.ai.kit.agents.analytics.AnalyticsResponse
import bosca.ai.kit.agents.chat.ChatRequest
import bosca.ai.kit.agents.chat.ChatResponse
import bosca.ai.kit.agents.description.DescriptionRequest
import bosca.ai.kit.agents.description.DescriptionResponse
import bosca.ai.kit.agents.graphql.GraphQLRequest
import bosca.ai.kit.agents.graphql.GraphQLResponse
import bosca.ai.kit.agents.graphql.GraphQLSummary
import bosca.ai.kit.agents.image.ImageRequest
import bosca.ai.kit.agents.image.ImageResponse
import bosca.ai.kit.agents.readingtime.ReadingTimeRequest
import bosca.ai.kit.agents.readingtime.ReadingTimeResponse
import bosca.ai.kit.agents.routing.RouteRequest
import bosca.ai.kit.agents.routing.RouteResponse
import bosca.ai.kit.agents.script.ScriptRequest
import bosca.ai.kit.agents.script.ScriptResponse
import bosca.ai.kit.agents.topics.TopicsRequest
import bosca.ai.kit.agents.topics.TopicsResponse
import bosca.ai.kit.agents.writer.WriterDecision
import bosca.ai.kit.agents.writer.WriterRequest
import bosca.ai.kit.agents.writer.WriterResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Koog's [Json], built **off the Bosca-provided platform [json]** so it inherits every Bosca serializer
 * (contextual UUID/date serializers, the content-relationship polymorphics, the class discriminator,
 * lenient parsing, …) and then adds the Koog polymorphic types that Koog resolves **reflectively**.
 *
 * Koog's `KotlinxSerializer` resolves a sealed Koog *interface* (e.g. `Message`) to an OPEN
 * `PolymorphicSerializer`, which consults the [SerializersModule] for its subtypes rather than the
 * sealed serializer — so without registering them you get *"Class discriminator was missing and no
 * default serializers were registered in the polymorphic scope of 'Message'"*. We register every Koog
 * message hierarchy that can be resolved this way: [Message], [MessagePart], and [MessageMetaInfo].
 *
 * Note: `serializersModule = json.serializersModule + …` (NOT `+=`) — `Json(json)` already seeds the
 * builder with the platform module, so `+=` would merge it onto itself and throw on the duplicate
 * `UUID`/date contextual registrations.
 *
 * The `contextual` block exists for the **checkpoint persistence** path in the native image. Koog's
 * `Persistence` feature serializes each node's output (and the planner state) with the node's reified
 * `KotlinTypeToken`, which `KotlinxSerializer` resolves via the reflective `serializersModule.serializer(KType)`.
 * In the native image the compiled-serializer lookup (a Java-reflection Companion lookup) returns null
 * for these classes, and kotlinx then falls back to the module's **contextual** registrations — so every
 * type that appears as a node output or planner state across Kit's strategies is registered here. On the
 * JVM the compiled lookup wins first, so these entries change nothing. A type missing from this list
 * degrades to Koog's "Failed to serialize output … skipping" WARN (checkpoint skipped, agent unaffected).
 * When adding a sub-agent, register its request/response/decision types here.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun koogJson(json: Json): Json = Json(json) {
    classDiscriminator = "type"
    classDiscriminatorMode = ClassDiscriminatorMode.POLYMORPHIC
    serializersModule = json.serializersModule + SerializersModule {
        polymorphic(Message::class) {
            subclass(Message.System::class, Message.System.serializer())
            subclass(Message.User::class, Message.User.serializer())
            subclass(Message.Assistant::class, Message.Assistant.serializer())
        }
        polymorphic(MessagePart::class) {
            subclass(MessagePart.Text::class, MessagePart.Text.serializer())
            subclass(MessagePart.Attachment::class, MessagePart.Attachment.serializer())
            subclass(MessagePart.Reasoning::class, MessagePart.Reasoning.serializer())
            subclass(MessagePart.Tool.Call::class, MessagePart.Tool.Call.serializer())
            subclass(MessagePart.Tool.Result::class, MessagePart.Tool.Result.serializer())
        }
        polymorphic(MessageMetaInfo::class) {
            subclass(RequestMetaInfo::class, RequestMetaInfo.serializer())
            subclass(ResponseMetaInfo::class, ResponseMetaInfo.serializer())
        }

        // Koog node IO types (tool-calling loop nodes).
        contextual(Message.Assistant::class, Message.Assistant.serializer())
        contextual(ToolCalls::class, ToolCalls.serializer())
        contextual(ReceivedToolResults::class, ReceivedToolResults.serializer())

        // Kit planner state and orchestrator IO.
        contextual(KitState::class, KitState.serializer())
        contextual(KitRequest::class, KitRequest.serializer())
        contextual(KitResponse::class, KitResponse.serializer())

        // Sub-agent strategy IO types (node inputs/outputs and structured outputs).
        contextual(AnalyticsRequest::class, AnalyticsRequest.serializer())
        contextual(AnalyticsResponse::class, AnalyticsResponse.serializer())
        contextual(ChatRequest::class, ChatRequest.serializer())
        contextual(ChatResponse::class, ChatResponse.serializer())
        contextual(DescriptionRequest::class, DescriptionRequest.serializer())
        contextual(DescriptionResponse::class, DescriptionResponse.serializer())
        contextual(GraphQLRequest::class, GraphQLRequest.serializer())
        contextual(GraphQLResponse::class, GraphQLResponse.serializer())
        contextual(GraphQLSummary::class, GraphQLSummary.serializer())
        contextual(ImageRequest::class, ImageRequest.serializer())
        contextual(ImageResponse::class, ImageResponse.serializer())
        contextual(ReadingTimeRequest::class, ReadingTimeRequest.serializer())
        contextual(ReadingTimeResponse::class, ReadingTimeResponse.serializer())
        contextual(RouteRequest::class, RouteRequest.serializer())
        contextual(RouteResponse::class, RouteResponse.serializer())
        contextual(ScriptRequest::class, ScriptRequest.serializer())
        contextual(ScriptResponse::class, ScriptResponse.serializer())
        contextual(TopicsRequest::class, TopicsRequest.serializer())
        contextual(TopicsResponse::class, TopicsResponse.serializer())
        contextual(WriterDecision::class, WriterDecision.serializer())
        contextual(WriterRequest::class, WriterRequest.serializer())
        contextual(WriterResponse::class, WriterResponse.serializer())
    }
}
