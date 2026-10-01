package bosca.server.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream

/**
 * Loads and provides access to hierarchical YAML-style configuration properties.
 *
 * Supports environment variable substitution using the pattern `$VAR:default` or `${VAR:default}`,
 * and nested property access via dot-separated paths (e.g., "database.postgres.url").
 *
 * This replaces Ktor's ApplicationConfig and YAML configuration loading with a standalone
 * implementation backed by SnakeYAML for parsing and kotlinx.serialization for typed access.
 */
class ApplicationConfig private constructor(private val root: JsonObject) {

    /**
     * Returns the [ConfigValue] at the given dot-separated [path].
     * Throws [IllegalStateException] if the property does not exist.
     */
    fun property(path: String): ConfigValue {
        return propertyOrNull(path) ?: throw IllegalStateException("Missing config property: $path")
    }

    /**
     * Returns the [ConfigValue] at the given dot-separated [path], or null if not present.
     */
    fun propertyOrNull(path: String): ConfigValue? {
        val element = resolve(root, path) ?: return null
        return ConfigValue(element, json)
    }

    private fun resolve(obj: JsonObject, path: String): JsonElement? {
        val parts = path.split(".")
        var current: JsonElement = obj
        for (part in parts) {
            current = when (current) {
                is JsonObject -> current[part] ?: return null
                else -> return null
            }
        }
        return current
    }

    companion object {
        private val log = LoggerFactory.getLogger(ApplicationConfig::class.java)
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Loads configuration from a YAML input stream, resolving environment variable references.
         */
        fun load(inputStream: InputStream): ApplicationConfig {
            val yaml = org.yaml.snakeyaml.Yaml()
            @Suppress("UNCHECKED_CAST")
            val raw = yaml.load<Any>(inputStream) as? Map<String, Any?> ?: emptyMap()
            val resolved = resolveEnvVars(raw)
            val jsonElement = toJsonElement(resolved)
            return ApplicationConfig(jsonElement as? JsonObject ?: JsonObject(emptyMap()))
        }

        /**
         * Loads configuration from the classpath resource at the given [resourcePath].
         */
        fun loadFromClasspath(resourcePath: String = "application.yaml"): ApplicationConfig {
            val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)
                ?: throw IllegalStateException("Configuration file not found on classpath: $resourcePath")
            return stream.use { load(it) }
        }

        /**
         * Loads configuration from a file on the filesystem at the given [path].
         */
        fun loadFromFile(path: String): ApplicationConfig {
            val file = File(path)
            if (!file.exists()) {
                throw IllegalStateException("Configuration file not found: $path")
            }
            return file.inputStream().use { load(it) }
        }

        private fun resolveEnvVars(map: Map<String, Any?>): Map<String, Any?> {
            return map.mapValues { (_, value) -> resolveValue(value) }
        }

        private fun resolveValue(value: Any?): Any? {
            return when (value) {
                is String -> resolveStringEnvVars(value)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    resolveEnvVars(value as Map<String, Any?>)
                }
                is List<*> -> value.map { resolveValue(it) }
                else -> value
            }
        }

        private fun resolveStringEnvVars(value: String): Any? {
            // Handle ${VAR:default} pattern
            val bracePattern = Regex("""\$\{([^}]+)}""")
            // Handle $VAR:default or $VAR pattern (whole string)
            val dollarPattern = Regex("""^\$([A-Za-z_][A-Za-z0-9_]*)(?::(.*))?$""")

            // First try full string match for $VAR:default or $VAR
            dollarPattern.matchEntire(value)?.let { match ->
                val envVar = match.groupValues[1]
                val default = match.groupValues[2]
                val resolved = environmentValue(envVar, default)
                return coerce(resolved)
            }

            // Then try ${VAR:default} replacements
            if (bracePattern.containsMatchIn(value)) {
                var result = value
                bracePattern.findAll(value).forEach { match ->
                    val content = match.groupValues[1]
                    val parts = content.split(":", limit = 2)
                    val envVar = parts[0]
                    val default = if (parts.size > 1) parts[1] else ""
                    val replacement = environmentValue(envVar, default)
                    result = result.replace(match.value, replacement)
                }
                return coerce(result)
            }

            return value
        }

        private fun environmentValue(name: String, default: String): String {
            val value = System.getenv(name)
            return if (value.isNullOrEmpty()) default else value
        }

        /**
         * Attempts to coerce a resolved string into its natural scalar type
         * (Boolean, Long, Double) so that [toJsonElement] produces a typed
         * [JsonPrimitive] instead of always producing a string.
         */
        private fun coerce(value: String): Any {
            if (value.equals("true", ignoreCase = true)) return true
            if (value.equals("false", ignoreCase = true)) return false
            value.toLongOrNull()?.let { return it }
            value.toDoubleOrNull()?.let { return it }
            return value
        }

        private fun toJsonElement(value: Any?): JsonElement {
            return when (value) {
                null -> JsonPrimitive(null as String?)
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is String -> JsonPrimitive(value)
                is Map<*, *> -> {
                    val map = value.entries.associate { (k, v) ->
                        k.toString() to toJsonElement(v)
                    }
                    JsonObject(map)
                }
                is List<*> -> JsonArray(value.map { toJsonElement(it) })
                else -> JsonPrimitive(value.toString())
            }
        }
    }
}

/**
 * Wraps a single configuration property value, providing typed access methods
 * for extracting the value as a String, List, or deserialized object.
 */
class ConfigValue(@PublishedApi internal val element: JsonElement, @PublishedApi internal val json: Json) {

    /** Returns the value as a string. For objects/arrays, returns the JSON representation. */
    fun getString(): String = when (element) {
        is JsonPrimitive -> element.content
        else -> element.toString()
    }

    /** Returns the value as a list of strings. Works with JSON arrays. */
    fun getList(): List<String> = when (element) {
        is JsonArray -> element.map { elem ->
            when (elem) {
                is JsonPrimitive -> elem.content
                else -> elem.toString()
            }
        }
        is JsonPrimitive -> listOf(element.content)
        else -> listOf(element.toString())
    }

    /**
     * Deserializes the value into the specified type [T] using kotlinx.serialization.
     * Useful for reading complex nested configuration objects.
     */
    inline fun <reified T> getAs(): T = json.decodeFromJsonElement(serializer<T>(), element)
}
