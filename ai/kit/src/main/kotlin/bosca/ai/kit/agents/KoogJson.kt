package bosca.ai.kit.agents

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessageMetaInfo
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
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
    }
}
