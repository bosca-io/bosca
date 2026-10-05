package bosca.security.oauth2

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Deserializes an `email_verified`-style flag that OAuth2/OIDC providers encode
 * inconsistently: Google's `tokeninfo` endpoint returns the **string** `"true"`, while
 * OIDC `userinfo` responses (and Apple) return a JSON **boolean** `true`.
 *
 * Accepts either form and normalizes to [Boolean]. Anything that is not boolean-`true`
 * or the (case-insensitive) string `"true"` — including a numeric, null, or absent value —
 * is treated as `false`, which is the safe default for a verification flag.
 */
object LenientBooleanSerializer : KSerializer<Boolean> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        // Outside a JSON context fall back to a strict boolean read.
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return false
        primitive.booleanOrNull?.let { return it }
        return primitive.content.equals("true", ignoreCase = true)
    }

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeBoolean(value)
    }
}
