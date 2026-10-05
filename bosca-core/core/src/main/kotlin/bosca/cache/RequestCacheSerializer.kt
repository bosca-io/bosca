package bosca.cache

import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.reflect.KClass

/**
 * Serializes and deserializes arbitrary objects for the request-scoped cache.
 *
 * The request cache stores intermediate results within a single GraphQL request
 * to avoid redundant database or service calls. This serializer handles the
 * conversion between in-memory objects and their string-based cache representation.
 */
interface RequestCacheSerializer {

    /** Converts [value] to its string representation for caching, or `null` for null values. */
    fun serialize(value: Any?): String?

    /** Reconstructs an object from its cached string [value], or `null` if [value] is null. */
    fun deserialize(value: String?): Any?

    companion object {

        @OptIn(ExperimentalSerializationApi::class, InternalDI::class)
        fun register() {
            ProviderRegistry.register(
                RequestCacheSerializer::class,
                object : ObjectProvider<RequestCacheSerializer> {
                    override val type: KClass<RequestCacheSerializer> = RequestCacheSerializer::class
                    override suspend fun get(): RequestCacheSerializer = RequestCacheSerializerImpl(ProviderRegistry.get())
                },
                true
            )
        }
    }
}