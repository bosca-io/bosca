package bosca.graphql.dispatcher

import bosca.graphql.server.RuntimeWiringBuilder
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.TestOnly

object DispatchersRegistry {

    private val dispatchers: ConcurrentHashMap<String, Dispatcher> = ConcurrentHashMap()

    val types: List<String>
        get() = dispatchers.keys.toList()

    operator fun get(type: String): Dispatcher = dispatchers[type] ?: error("Dispatcher not found for type: $type")

    /**
     * Registers all generated dispatchers. When several modules extend the same GraphQL type, their field resolvers
     * are merged in registrar order and later resolvers win deterministic name collisions.
     */
    suspend fun register(builder: RuntimeWiringBuilder, vararg registrars: DispatchersRegistrar) {
        val byType = LinkedHashMap<String, MutableList<Dispatcher>>()
        registrars.forEach { registrar ->
            registrar.dispatchers().forEach { (type, dispatcher) ->
                byType.getOrPut(type) { mutableListOf() }.add(dispatcher)
            }
        }
        byType.forEach { (type, parts) ->
            val merged = if (parts.size == 1) parts.single() else CompositeDispatcher(parts)
            dispatchers[type] = merged
            builder.type(merged.type)
        }
    }

    @TestOnly
    fun clear() {
        dispatchers.clear()
    }
}

/** Provides the generated dispatchers contributed by one module. */
interface DispatchersRegistrar {
    /** Return dispatchers keyed by GraphQL type name. */
    suspend fun dispatchers(): Map<String, Dispatcher>
}
