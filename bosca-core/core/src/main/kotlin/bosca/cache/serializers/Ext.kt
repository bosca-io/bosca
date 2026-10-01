package bosca.cache.serializers

private const val CACHE_KEY_SEPARATOR = "::"

class CacheKeyBuilder(private val prefix: Boolean) {

    private val str = StringBuilder()
    private var first = true

    fun appendKeyPrefix(part: String, cacheName: String) {
        str.append(part)
        str.append(CACHE_KEY_SEPARATOR)
        str.append(cacheName)
    }

    fun appendKeyPart(part: Any?) {
        if (prefix) {
            if (!first) {
                return
            } else {
                first = false
                if (part == null) error("Key prefix cannot be null")
            }
        }
        if (part == null) {
            str.append(CACHE_KEY_SEPARATOR)
            return
        }
        str.append(CACHE_KEY_SEPARATOR)
        str.append(part)
    }

    override fun toString(): String {
        return str.toString()
    }
}

fun buildCacheKey(prefix: Boolean, block: CacheKeyBuilder.() -> Unit): String {
    return CacheKeyBuilder(prefix).apply(block).toString()
}

fun String.separateForCacheKey(): List<String> {
    val parts = split(CACHE_KEY_SEPARATOR)
    return parts.subList(1, parts.size)
}