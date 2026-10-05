package bosca.di

import kotlin.reflect.KClass

/**
 * Provides suspend-based access to a dependency of type [T] within the DI system.
 *
 * Implementations wrap the creation or retrieval logic for a specific dependency,
 * allowing the DI container to resolve instances asynchronously.
 *
 * @param T the type of object this provider supplies, must be non-null
 */
interface ObjectProvider<T : Any> {

    /** The [KClass] token identifying the type this provider supplies. */
    val type: KClass<T>

    /** Whether this provider currently has a value available. Defaults to `true`. */
    val exists: Boolean
        get() = true

    /**
     * Retrieves or creates the instance managed by this provider.
     *
     * Suspend because resolution may involve I/O or other async initialization.
     *
     * @return the resolved instance of type [T]
     */
    suspend fun get(): T
}

fun <T : Any> ObjectProvider<T>.getBlocking(): T = runBlocking { get() }

inline fun <reified T : Any> T.asProvider(): ObjectProvider<T> = object : ObjectProvider<T> {
    override val type: KClass<T> = T::class
    override suspend fun get(): T = this@asProvider
}
