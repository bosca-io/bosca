package bosca.serialization

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializerOrNull
import kotlin.reflect.KClass

/**
 * Resolves a [KSerializer] for the given class, preferring the build-time
 * [SerializerCache] (populated by BoscaFeature at native image build time)
 * and falling back to kotlinx.serialization's reflective lookup.
 *
 * Use this instead of `KClass.serializer()` or `KClass.serializerOrNull()` in any
 * code path that must work in GraalVM native images.
 */
@OptIn(InternalSerializationApi::class)
fun <T : Any> resolveSerializerOrNull(kClass: KClass<T>): KSerializer<T>? {
    SerializerCache.get(kClass.java)?.let { return it }
    return kClass.serializerOrNull()
}
