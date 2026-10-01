package bosca.cache

import bosca.serialization.SerializerCache
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * The stored envelope, `{"className":…,"collection":true,"data":…}`, with `collection` present only when `true`.
 * Every field is optional and nullable. [EnvelopeWriter] and [EnvelopeReader] handle it in one pass;
 * [EnvelopeTreeReader] keeps `data` as a [JsonElement] tree for the fallback path.
 */
private val envelopeDescriptor: SerialDescriptor = buildClassSerialDescriptor("bosca.cache.RequestCacheEnvelope") {
    element("className", String.serializer().nullable.descriptor, isOptional = true)
    element<Boolean>("collection", isOptional = true)
    element("data", JsonElement.serializer().nullable.descriptor, isOptional = true)
}

private const val CLASS_NAME_INDEX = 0
private const val COLLECTION_INDEX = 1
private const val DATA_INDEX = 2

private class EnvelopeWriter(
    private val className: String,
    private val collection: Boolean,
    private val data: SerializationStrategy<Any>,
) : SerializationStrategy<Any> {

    override val descriptor: SerialDescriptor = envelopeDescriptor

    override fun serialize(encoder: Encoder, value: Any) = encoder.encodeStructure(descriptor) {
        encodeStringElement(descriptor, CLASS_NAME_INDEX, className)
        if (collection) encodeBooleanElement(descriptor, COLLECTION_INDEX, true)
        encodeSerializableElement(descriptor, DATA_INDEX, data, value)
    }
}

/** Signals an envelope the single-pass reader does not handle; the caller then uses the tree-based reader. */
private class EnvelopeLayoutException(message: String) : SerializationException(message)

private class EnvelopeReader(
    private val serializerFor: (className: String) -> KSerializer<Any>,
) : DeserializationStrategy<Any?> {

    override val descriptor: SerialDescriptor = envelopeDescriptor

    override fun deserialize(decoder: Decoder): Any? = decoder.decodeStructure(descriptor) {
        var serializer: KSerializer<Any>? = null
        var collection = false
        var dataRead = false
        var result: Any? = null
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CLASS_NAME_INDEX -> serializer = serializerFor(decodeStringElement(descriptor, CLASS_NAME_INDEX))
                COLLECTION_INDEX -> {
                    collection = decodeBooleanElement(descriptor, COLLECTION_INDEX)
                    if (dataRead) throw EnvelopeLayoutException("collection follows data")
                }
                DATA_INDEX -> {
                    val element = serializer ?: throw EnvelopeLayoutException("data precedes className")
                    result = decodeNullableSerializableElement(
                        descriptor,
                        DATA_INDEX,
                        if (collection) ListSerializer(element) else element,
                    )
                    dataRead = true
                }
                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException("Unexpected request cache envelope element $index")
            }
        }
        result
    }
}

/** A stored envelope with its payload still as a [JsonElement] tree. */
private class TreeEnvelope(val className: String?, val collection: Boolean, val data: JsonElement?)

private object EnvelopeTreeReader : DeserializationStrategy<TreeEnvelope> {

    override val descriptor: SerialDescriptor = envelopeDescriptor

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): TreeEnvelope = decoder.decodeStructure(descriptor) {
        var className: String? = null
        var collection = false
        var data: JsonElement? = null
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CLASS_NAME_INDEX -> className = decodeNullableSerializableElement(descriptor, CLASS_NAME_INDEX, String.serializer().nullable)
                COLLECTION_INDEX -> collection = decodeBooleanElement(descriptor, COLLECTION_INDEX)
                DATA_INDEX -> data = decodeNullableSerializableElement(descriptor, DATA_INDEX, JsonElement.serializer().nullable)
                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException("Unexpected request cache envelope element $index")
            }
        }
        TreeEnvelope(className, collection, data)
    }
}

class RequestCacheSerializerImpl
@OptIn(ExperimentalSerializationApi::class)
constructor(private val json: Json) : RequestCacheSerializer {

    companion object {
        private val log = LoggerFactory.getLogger(RequestCacheSerializerImpl::class.java)
    }

    // Resolved once per stored class name / value class, instead of a Class.forName and serializer lookup per value.
    private val serializersByStoredName = ConcurrentHashMap<String, KSerializer<Any>>()
    private val serializersByClass = ConcurrentHashMap<Class<*>, KSerializer<Any>>()
    private val envelopeReader = EnvelopeReader(::serializerForStoredName)

    override fun serialize(value: Any?): String? {
        if (value == null) return null
        if (value is Collection<*>) {
            val first = value.firstOrNull() ?: return null
            val serializer = serializerForValueClass(first.javaClass)

            @Suppress("UNCHECKED_CAST")
            val list = (value as? List<*> ?: value.toList()) as List<Any>
            @Suppress("UNCHECKED_CAST")
            val listSerializer = ListSerializer(serializer) as SerializationStrategy<Any>
            return json.encodeToString(EnvelopeWriter(serializer.descriptor.serialName, true, listSerializer), list)
        }
        val serializer = serializerForValueClass(value.javaClass)
        return json.encodeToString(EnvelopeWriter(serializer.descriptor.serialName, false, serializer), value)
    }

    override fun deserialize(value: String?): Any? {
        if (value == null) return null
        if (value.isEmpty()) return null
        return try {
            json.decodeFromString(envelopeReader, value)
        } catch (e: Exception) {
            // The single-pass reader cannot tell a malformed envelope (which must propagate) from a payload that no
            // longer matches its class (which is logged and treated as absent). The tree-based reader decides both
            // exactly as before, so any failure is re-read there.
            deserializeFromTree(value)
        }
    }

    private fun deserializeFromTree(value: String): Any? {
        val envelope = json.decodeFromString(EnvelopeTreeReader, value)
        val data = envelope.data
        if (data == null || data == JsonNull) return null
        envelope.className?.let { className ->
            val serializer = serializerForStoredName(className)
            try {
                return if (envelope.collection) {
                    json.decodeFromJsonElement(ListSerializer(serializer), data)
                } else {
                    json.decodeFromJsonElement(serializer, data)
                }
            } catch (e: Exception) {
                log.warn("Failed to deserialize object of type {}", className, e)
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializerForValueClass(clazz: Class<*>): KSerializer<Any> = serializersByClass.computeIfAbsent(clazz) {
        if (clazz == UUID::class.java) UUIDSerializer() as KSerializer<Any> else resolveSerializer(clazz.kotlin)
    }

    /** Resolution failures (such as an unknown class) propagate and are not cached, so they recur on every read. */
    @Suppress("UNCHECKED_CAST")
    private fun serializerForStoredName(className: String): KSerializer<Any> = serializersByStoredName.computeIfAbsent(className) {
        if (className == "UUID") {
            UUIDSerializer() as KSerializer<Any>
        } else {
            resolveSerializer(resolveKClassFromStoredName(className) ?: Class.forName(className).kotlin)
        }
    }

    /**
     * Resolves a [KSerializer] for the given class, preferring the build-time
     * [SerializerCache] (populated by BoscaFeature at native image build time)
     * and falling back to kotlinx.serialization's reflective `KClass.serializer()`.
     */
    @OptIn(InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    private fun resolveSerializer(clazz: KClass<*>): KSerializer<Any> {
        SerializerCache.get(clazz.java)?.let { return it as KSerializer<Any> }
        return clazz.serializer() as KSerializer<Any>
    }

    // Resolve a KClass from a stored name, handling legacy and Kotlin serial names.
    private fun resolveKClassFromStoredName(name: String): KClass<*>? {
        // Legacy support: older cache might have stored "UUID"
        if (name == "UUID") return UUID::class

        // Backward compatibility for Kotlin serial names that aren't JVM-loadable
        return when (name) {
            "kotlin.String" -> String::class
            "kotlin.Int" -> Int::class
            "kotlin.Long" -> Long::class
            "kotlin.Boolean" -> Boolean::class
            "kotlin.Double" -> Double::class
            "kotlin.Float" -> Float::class
            "kotlin.Short" -> Short::class
            "kotlin.Byte" -> Byte::class
            "kotlin.Char" -> Char::class
            else -> try {
                // Prefer JVM class name going forward
                Class.forName(name).kotlin
            } catch (_: ClassNotFoundException) {
                null
            }
        }
    }
}
