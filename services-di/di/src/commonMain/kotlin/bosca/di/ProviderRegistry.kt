package bosca.di

import bosca.di.annotation.InternalDI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.seconds

class MissingProviderException(type: KClass<*>, name: String?) : Exception("Missing provider for: $type : $name")

/** Thrown when different provider sources claim the same type-and-name binding. */
class DuplicateProviderException(
    type: KClass<*>,
    name: String?,
    existingSource: KClass<*>,
    conflictingSource: KClass<*>,
) : Exception(
    "Duplicate provider for: $type : $name; sources: " +
        "${existingSource.description()}, ${conflictingSource.description()}"
)

private fun KClass<*>.description(): String = fullClassName(this).ifEmpty { toString() }

expect fun fullClassName(clazz: KClass<*>): String

suspend inline fun <reified T : Any> provide(): T {
    @OptIn(InternalDI::class)
    return ProviderRegistry.get<T>()
}

suspend inline fun <reified T : Any> provide(name: String): T {
    @OptIn(InternalDI::class)
    return ProviderRegistry.get<T>(name)
}

inline fun <reified T : Any> provideBlocking(): T {
    return runBlocking { provide<T>() }
}

inline fun <reified T : Any> provideBlockingNoSuspend(): T {
    return runBlockingNoSuspend { provide<T>() }
}

inline fun <reified T : Any> provideBlocking(name: String): T {
    return runBlocking { provide<T>(name) }
}

inline fun <reified T : Any> provideLazy(): Lazy<T> = lazy {
    provideBlocking<T>()
}

inline fun <reified T : Any> provideLazy(name: String): Lazy<T> = lazy {
    provideBlocking<T>(name)
}

inline fun <reified T : Any> provideProvider(): ObjectProvider<T> {
    @OptIn(InternalDI::class)
    return ProviderRegistry.getProvider<T>()
}

inline fun <reified T : Any> provideProvider(name: String): ObjectProvider<T> {
    @OptIn(InternalDI::class)
    return ProviderRegistry.getProvider<T>(name)
}

inline fun <reified T : Any> providerMissing() {
    @OptIn(InternalDI::class)
    ProviderRegistry.register(T::class, MissingObjectProvider(T::class, null))
}

/**
 * Registers an unnamed provider for [T].
 *
 * Set [overrideExisting] only when this call intentionally replaces a binding from a different
 * provider source. Ordinary duplicate registrations remain errors.
 */
inline fun <reified T : Any> provides(
    singleton: Boolean = false,
    overrideExisting: Boolean = false,
    crossinline block: suspend () -> T,
) {
    @OptIn(InternalDI::class)
    ProviderRegistry.register(T::class, object : ObjectProvider<T> {
        override val type: KClass<T> = T::class
        override suspend fun get(): T {
            return block()
        }
    }, singleton, overrideExisting)
}

/**
 * Registers a provider for the named [T] binding.
 *
 * Set [overrideExisting] only when this call intentionally replaces a binding from a different
 * provider source. Ordinary duplicate registrations remain errors.
 */
inline fun <reified T : Any> provides(
    name: String,
    singleton: Boolean = false,
    overrideExisting: Boolean = false,
    crossinline block: suspend () -> T,
) {
    @OptIn(InternalDI::class)
    ProviderRegistry.register(T::class, object : ObjectProvider<T> {
        override val type: KClass<T> = T::class
        override suspend fun get(): T {
            return block()
        }
    }, name, singleton, overrideExisting)
}

fun register(vararg providers: ProviderRegistrar) {
    @OptIn(InternalDI::class)
    ProviderRegistry.register(*providers)
}

@InternalDI
object ProviderRegistry {

    private val typeProviders = mutableMapOf<KClass<*>, ObjectProvider<*>>()
    private val nameProviders = mutableMapOf<String, ObjectProvider<*>>()
    private val typeProviderSources = mutableMapOf<KClass<*>, KClass<*>>()
    private val nameProviderSources = mutableMapOf<String, KClass<*>>()

    fun clear() {
        typeProviders.clear()
        nameProviders.clear()
        typeProviderSources.clear()
        nameProviderSources.clear()
    }

    fun register(vararg providers: ProviderRegistrar) {
        providers.forEach {
            it.register()
        }
    }

    /**
     * Registers a named binding. [overrideExisting] explicitly permits replacement of a binding
     * from a different provider source; generated registrars use the strict default.
     */
    fun <T : Any> register(
        clazz: KClass<T>,
        provider: ObjectProvider<T>,
        name: String,
        singleton: Boolean = false,
        overrideExisting: Boolean = false,
    ) {
        val key = "${fullClassName(clazz)}.$name"
        val existing = nameProviders[key]
        if (existing != null && !replaceMissingProvider(existing, provider)) {
            val existingSource = nameProviderSources.getValue(key)
            if (existingSource != provider::class && (!overrideExisting || provider is MissingObjectProvider<*>)) {
                throw DuplicateProviderException(clazz, name, existingSource, provider::class)
            }
        }
        nameProviders[key] = if (singleton) {
            SingletonObjectProvider(provider)
        } else {
            provider
        }
        nameProviderSources[key] = provider::class
    }

    /**
     * Registers an unnamed binding. [overrideExisting] explicitly permits replacement of a binding
     * from a different provider source; generated registrars use the strict default.
     */
    fun <T : Any> register(
        clazz: KClass<T>,
        provider: ObjectProvider<T>,
        singleton: Boolean = false,
        overrideExisting: Boolean = false,
    ) {
        val existing = typeProviders[clazz]
        if (existing != null && !replaceMissingProvider(existing, provider)) {
            val existingSource = typeProviderSources.getValue(clazz)
            if (existingSource != provider::class && (!overrideExisting || provider is MissingObjectProvider<*>)) {
                throw DuplicateProviderException(clazz, null, existingSource, provider::class)
            }
        }
        typeProviders[clazz] = if (singleton) {
            SingletonObjectProvider(provider)
        } else {
            provider
        }
        typeProviderSources[clazz] = provider::class
    }

    /**
     * Use provide instead of this function
     */
    fun <T : Any> get(type: KClass<T>): ObjectProvider<T> {
        val provider = typeProviders[type] ?: MissingObjectProvider(type, null)
        @Suppress("UNCHECKED_CAST")
        return provider as ObjectProvider<T>
    }

    /**
     * Use provide instead of this function
     */
    fun <T : Any> get(type: KClass<T>, name: String): ObjectProvider<T> {
        val provider = nameProviders["${fullClassName(type)}.$name"] ?: MissingObjectProvider(type, name)
        @Suppress("UNCHECKED_CAST")
        return provider as ObjectProvider<T>
    }

    /**
     * Use provide instead of this function
     */
    suspend inline fun <reified T : Any> get(): T = get(T::class).get()

    /**
     * Use provide instead of this function
     */
    suspend inline fun <reified T : Any> get(name: String): T = get(T::class, name).get()

    /**
     * Use provideProvider instead of this function
     */
    inline fun <reified T : Any> getProvider(): ObjectProvider<T> = get(T::class)

    /**
     * Use provideProvider instead of this function
     */
    inline fun <reified T : Any> getProvider(name: String): ObjectProvider<T> = get(T::class, name)

    /** Number of type-keyed providers currently registered. */
    val typeProviderCount: Int get() = typeProviders.size

    /** Number of name-keyed providers currently registered. */
    val namedProviderCount: Int get() = nameProviders.size

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> findAll(type: KClass<T>): List<ObjectProvider<T>> {
        val items = mutableListOf<ObjectProvider<T>>()
        typeProviders[type]?.let {
            items.add(it as ObjectProvider<T>)
        }
        nameProviders.values.asSequence().filter { it.type == type }.forEach { items.add(it as ObjectProvider<T>) }
        return items
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> findAllWithNames(type: KClass<T>): Map<String, ObjectProvider<T>> {
        val items = mutableMapOf<String, ObjectProvider<T>>()
        typeProviders[type]?.let {
            items[""] = it as ObjectProvider<T>
        }
        val prefix = "${fullClassName(type)}."
        nameProviders.entries.asSequence()
            .filter { it.value.type == type && it.key.startsWith(prefix) }
            .forEach {
                val name = it.key.removePrefix(prefix)
                items[name] = it.value as ObjectProvider<T>
            }
        return items
    }

    private fun replaceMissingProvider(existing: ObjectProvider<*>, provider: ObjectProvider<*>): Boolean {
        if (provider is MissingObjectProvider<*>) return false
        return existing is MissingObjectProvider<*>
    }
}

private class SingletonObjectProvider<T : Any>(private val provider: ObjectProvider<T>) : ObjectProvider<T> {

    private val mutex = Mutex()
    private var instance: T? = null

    override val type: KClass<T> = provider.type

    override val exists: Boolean
        get() = provider.exists

    override suspend fun get(): T {
        instance?.let { return it }
        mutex.lock()
        try {
            instance?.let { return it }
            withTimeout(8.seconds) {
                instance = provider.get()
            }
        } finally {
            mutex.unlock()
        }
        return instance ?: error("Singleton provider returned null")
    }
}

@InternalDI
class MissingObjectProvider<T : Any>(override val type: KClass<T>, private val name: String?) : ObjectProvider<T> {
    override suspend fun get(): T = throw MissingProviderException(type, name)

    override val exists: Boolean
        get() = false
}
