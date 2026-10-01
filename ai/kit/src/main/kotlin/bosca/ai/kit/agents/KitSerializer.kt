package bosca.ai.kit.agents

import ai.koog.prompt.structure.StructuredResponse
import ai.koog.serialization.JSONArray
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONLiteral
import ai.koog.serialization.JSONNull
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.JavaClassToken
import ai.koog.serialization.JavaTypeToken
import ai.koog.serialization.KSerializerTypeToken
import ai.koog.serialization.KotlinClassToken
import ai.koog.serialization.KotlinTypeToken
import ai.koog.serialization.TypeToken
import ai.koog.serialization.annotations.InternalKoogSerializationApi
import ai.koog.serialization.kotlinx.JSONArraySerializer
import ai.koog.serialization.kotlinx.JSONElementSerializer
import ai.koog.serialization.kotlinx.JSONLiteralSerializer
import ai.koog.serialization.kotlinx.JSONNullSerializer
import ai.koog.serialization.kotlinx.JSONObjectSerializer
import ai.koog.serialization.kotlinx.JSONPrimitiveSerializer
import kotlin.reflect.KClass

/**
 * Wraps koog's serializer to keep every path off reflective serializer lookup, which fails in the
 * native image. Koog's JSON element types are `@Serializable(with = ...)`; resolving them through a
 * reflective [TypeToken] misses the annotation in native and falls back to an empty polymorphic
 * scope (or "Serializer for class X is not found"), so any token targeting one of them is rewritten
 * to its explicit serializer before delegating.
 */
internal class KitSerializer(
    private val serializer: JSONSerializer
) : JSONSerializer {

    override fun <T> decodeFromJSONElement(value: JSONElement, typeToken: TypeToken): T {
        try {
            return serializer.decodeFromJSONElement(value, typeToken.withExplicitJSONElementSerializer())
        } catch (e: Throwable) {
            throw e
        }
    }

    override fun <T> decodeFromString(value: String, typeToken: TypeToken): T {
        try {
            return serializer.decodeFromString(value, typeToken.withExplicitJSONElementSerializer())
        } catch (e: Throwable) {
            throw e
        }
    }

    @OptIn(InternalKoogSerializationApi::class)
    override fun decodeJSONElementFromString(value: String): JSONElement {
        try {
            return serializer.decodeFromString(value, KSerializerTypeToken(JSONElementSerializer))
        } catch (e: Throwable) {
            throw e
        }
    }

    @OptIn(InternalKoogSerializationApi::class)
    override fun encodeJSONElementToString(value: JSONElement): String {
        try {
            return serializer.encodeToString(value, KSerializerTypeToken(JSONElementSerializer))
        } catch (e: Throwable) {
            throw e
        }
    }

    @OptIn(InternalKoogSerializationApi::class)
    override fun <T> encodeToJSONElement(value: T, typeToken: TypeToken): JSONElement {
        try {
            var typeToken = typeToken
            var value: Any = value as Any
            if (value is Result<*>) {
                @Suppress("UNCHECKED_CAST")
                value = value.getOrNull() as? T ?: return JSONNull
                if (value is StructuredResponse<*>) {
                    value = value.message
                }
                typeToken = TypeToken.of(value::class.java)
            }
            return serializer.encodeToJSONElement(value, typeToken.withExplicitJSONElementSerializer())
        } catch (e: Throwable) {
            throw e
        }
    }

    override fun <T> encodeToString(value: T, typeToken: TypeToken): String {
        try {
            return serializer.encodeToString(value, typeToken.withExplicitJSONElementSerializer())
        } catch (e: Throwable) {
            throw e
        }
    }

    @OptIn(InternalKoogSerializationApi::class)
    private fun TypeToken.withExplicitJSONElementSerializer(): TypeToken {
        val klass: KClass<*> = when (this) {
            is KotlinTypeToken -> type.classifier as? KClass<*>
            is KotlinClassToken -> klass
            is JavaTypeToken -> (type as? Class<*>)?.kotlin
            is JavaClassToken -> klass.kotlin
            is KSerializerTypeToken<*> -> return this
        } ?: return this
        val explicit = when (klass) {
            JSONElement::class -> JSONElementSerializer
            JSONObject::class -> JSONObjectSerializer
            JSONArray::class -> JSONArraySerializer
            JSONPrimitive::class -> JSONPrimitiveSerializer
            JSONLiteral::class -> JSONLiteralSerializer
            JSONNull::class -> JSONNullSerializer
            else -> return this
        }
        return KSerializerTypeToken(explicit)
    }
}
