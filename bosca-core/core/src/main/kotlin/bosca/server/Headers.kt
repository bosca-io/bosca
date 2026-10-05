package bosca.server

/**
 * Provides a read-only view of HTTP headers as a multimap.
 *
 * Header names are treated case-insensitively to comply with the HTTP specification.
 * Values are stored in the order they were added.
 */
interface Headers {
    /** Returns the first value for the given header [name], or null if not present. */
    operator fun get(name: String): String?

    /** Returns all values for the given header [name], or an empty list if not present. */
    fun getAll(name: String): List<String>

    /** Returns the set of all header names present. */
    fun names(): Set<String>

    /** Returns the header entries as a set of name to value-list mappings. */
    fun entries(): Set<Map.Entry<String, List<String>>>

    /** Returns true if there are no headers. */
    fun isEmpty(): Boolean

    /** Iterates over all headers, invoking [action] for each name and its list of values. */
    fun forEach(action: (String, List<String>) -> Unit)

    companion object {
        /** An empty headers instance containing no entries. */
        val Empty: Headers = MapHeaders(emptyMap())

        /**
         * Builds a [Headers] instance using a mutable builder, allowing headers
         * to be appended incrementally.
         */
        fun build(block: HeadersBuilder.() -> Unit): Headers {
            val builder = HeadersBuilder()
            builder.block()
            return builder.build()
        }
    }
}

/**
 * Creates a [Headers] instance from name to value-list pairs.
 */
fun headersOf(vararg pairs: Pair<String, List<String>>): Headers =
    if (pairs.isEmpty()) Headers.Empty
    else MapHeaders(pairs.associate { (k, v) -> k.lowercase() to v }, pairs.associate { it })

/**
 * Creates a [Headers] instance from a single name-value pair.
 */
fun headersOf(name: String, value: String): Headers =
    MapHeaders(mapOf(name.lowercase() to listOf(value)), mapOf(name to listOf(value)))

/**
 * Creates an empty [Headers] instance.
 */
fun headersOf(): Headers = Headers.Empty

/**
 * Mutable builder for constructing [Headers] instances incrementally.
 *
 * Header names are stored case-insensitively for lookup, while preserving
 * the original casing of the first occurrence for iteration.
 */
class HeadersBuilder {
    private val map = LinkedHashMap<String, MutableList<String>>()
    private val originalNames = LinkedHashMap<String, String>()

    /** Appends a [value] to the header with the given [name]. */
    fun append(name: String, value: String) {
        val key = name.lowercase()
        map.getOrPut(key) { mutableListOf() }.add(value)
        originalNames.putIfAbsent(key, name)
    }

    /** Builds an immutable [Headers] from the accumulated entries. */
    fun build(): Headers {
        val original = LinkedHashMap<String, List<String>>()
        for ((key, values) in map) {
            original[originalNames[key] ?: key] = values.toList()
        }
        return MapHeaders(map.mapValues { it.value.toList() }, original)
    }
}

/**
 * A [Headers] implementation backed by a case-insensitive lookup map and an
 * original-cased map for iteration.
 */
private class MapHeaders(
    private val lowercaseMap: Map<String, List<String>>,
    private val originalMap: Map<String, List<String>> = lowercaseMap
) : Headers {
    constructor(map: Map<String, List<String>>) : this(
        map.entries.associate { (k, v) -> k.lowercase() to v },
        map
    )

    override fun get(name: String): String? = lowercaseMap[name.lowercase()]?.firstOrNull()
    override fun getAll(name: String): List<String> = lowercaseMap[name.lowercase()] ?: emptyList()
    override fun names(): Set<String> = originalMap.keys
    override fun entries(): Set<Map.Entry<String, List<String>>> = originalMap.entries
    override fun isEmpty(): Boolean = lowercaseMap.isEmpty()
    override fun forEach(action: (String, List<String>) -> Unit) = originalMap.forEach(action)
}
