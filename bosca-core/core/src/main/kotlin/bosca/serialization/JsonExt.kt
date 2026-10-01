package bosca.serialization

import bosca.di.provide
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.sql.Date
import java.sql.Time
import java.sql.Timestamp

object JsonConverter {

    val json by lazy { runBlocking { provide<Json>() } }

    fun JsonElement.toAny(): Any? {
        return when (this) {
            is JsonNull -> null
            is JsonObject -> mapValues { it.value.toAny() }
            is JsonArray -> toAnyList()
            is JsonPrimitive -> when {
                isString -> content
                booleanOrNull != null -> boolean
                intOrNull != null -> int
                longOrNull != null -> long
                floatOrNull != null -> float
                doubleOrNull != null -> double
                else -> null
            }
        }
    }

    private fun JsonArray.toAnyList(): List<Any?> {
        val items = mutableListOf<Any?>()
        forEach { items.add(it.toAny()) }
        return items
    }

    fun Any?.toJsonElement(): JsonElement {
        if (this == null) return JsonNull
        @Suppress("UNCHECKED_CAST")
        return when (this) {
            is Map<*, *> -> JsonObject(mapValues { it.value?.toJsonElement() ?: JsonNull } as Map<String, JsonElement>)
            is List<*> -> JsonArray(map { it?.toJsonElement() ?: JsonNull })
            is Number -> JsonPrimitive(this)
            is String -> JsonPrimitive(this)
            is Boolean -> JsonPrimitive(this)
            is Timestamp -> JsonPrimitive(time)
            is Date -> JsonPrimitive(time)
            is Time -> JsonPrimitive(time)
            is UUID -> JsonPrimitive(toString())
            is JsonElement -> this
            else -> toJsonElement(json)
        }
    }

    fun Any.findSerializer(): KSerializer<Any?> {
        if (this is List<*>) {
            if (this.isEmpty()) {
                return AnyNullableSerializer()
            }
            val first = first() ?: error("first value null")
            val serializer = resolveSerializerOrNull(first::class) ?: error("missing serializer for ${first::class.qualifiedName}")
            @Suppress("UNCHECKED_CAST")
            return ListSerializer(serializer as KSerializer<Any>) as KSerializer<Any?>
        }
        @Suppress("UNCHECKED_CAST")
        return (resolveSerializerOrNull(this::class) ?: error("missing serializer for ${this::class.qualifiedName}")) as KSerializer<Any?>
    }

    private fun Any.toJsonElement(json: Json): JsonElement {
        if (this is List<*>) {
            if (this.isEmpty()) {
                return JsonArray(emptyList())
            }
            val first = first() ?: error("first value null")
            val serializer = resolveSerializerOrNull(first::class) ?: error("missing serializer for ${first::class.qualifiedName}")
            @Suppress("UNCHECKED_CAST")
            return json.encodeToJsonElement(ListSerializer(serializer as KSerializer<Any>) as KSerializer<Any>, this)
        }

        return resolveSerializerOrNull(this::class)?.let { serializer ->
            @Suppress("UNCHECKED_CAST")
            json.encodeToJsonElement(serializer as KSerializer<Any>, this)
        } ?: error("missing serializer for ${this::class.qualifiedName}")
    }

    fun String.parseToJsonElement(): JsonElement = json.parseToJsonElement(this)

    fun toJsonElement(json: String): JsonElement = json.parseToJsonElement()

    inline fun <reified T> T.asJsonElement(): JsonElement = json.encodeToJsonElement(this)

    inline fun <reified T> JsonElement.asValue(): T = json.decodeFromJsonElement(this)
}


