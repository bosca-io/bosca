package bosca.cache

/**
 * Holds a single cached entry's string payload along with its existence status.
 */
interface CacheValue {

    /** The serialized string payload, or `null` if the entry has no value. */
    val value: String?

    /** Whether an entry was found in the cache for the requested key. */
    val exists: Boolean
}

/**
 * Converts between typed keys of kind [K] and the local/remote key representations
 * used by the caching infrastructure.
 *
 * @param K the application-level key type (e.g. UUID, String, Long)
 */
interface CacheKeySerializer<K> {

    /**
     * Wraps a raw key value into a [CacheKey] scoped to [cacheName].
     *
     * @param cacheName the logical cache this key belongs to
     * @param value the raw key value
     * @return a [CacheKey] suitable for local cache lookups
     */
    fun toLocalKey(cacheName: String, value: K): CacheKey<K>

    /**
     * Produces the full remote-store key string for the given [CacheKey].
     * Defaults to [CacheKey.toRemoteKey].
     */
    fun toRemoteKey(key: CacheKey<K>): String = key.toRemoteKey()

    /**
     * Produces the prefix form of the remote key, used for prefix-based eviction.
     * Defaults to [CacheKey.toRemoteKeyPrefix].
     */
    fun toRemoteKeyPrefix(key: CacheKey<K>): String = key.toRemoteKeyPrefix()

    /**
     * Reconstructs a [CacheKey] from its remote-store string representation.
     *
     * @param key the remote key string
     * @return the corresponding [CacheKey]
     */
    fun fromRemoteKey(key: String): CacheKey<K>
}

/**
 * A two-tier cache (local + remote) keyed by [K] that stores values as serialized strings.
 *
 * Supports single-entry and batch operations, as well as expiration-based eviction.
 *
 * @param K the application-level key type
 */
interface Cache<K> {

    /** The serializer used to translate between typed keys and their string representations. */
    val keySerializer: CacheKeySerializer<K>

    /**
     * Retrieves a single entry. The returned [CacheValue.exists] indicates whether the key was found.
     */
    suspend fun get(key: CacheKey<K>): CacheValue

    /**
     * Retrieves multiple entries using the backend's batch strategy. Ordering matches the input [keys] list.
     */
    suspend fun getBatch(keys: List<CacheKey<K>>): List<CacheValue>

    /**
     * Atomically retrieves [key] and renews its expiration when it exists.
     *
     * Implementations must not emulate this as a separate [get] followed by [put], because that can overwrite
     * a concurrent update with the value observed by the read.
     */
    suspend fun getAndTouch(key: CacheKey<K>): CacheValue {
        throw UnsupportedOperationException("Atomic get-and-touch is not supported by this cache")
    }

    /**
     * Retrieves and atomically renews multiple entries using the backend's batch strategy.
     * Ordering matches the input [keys] list.
     */
    suspend fun getBatchAndTouch(keys: List<CacheKey<K>>): List<CacheValue> {
        throw UnsupportedOperationException("Atomic batch get-and-touch is not supported by this cache")
    }

    /**
     * Stores a value (or `null` to represent a negative cache entry) under the given key.
     */
    suspend fun put(key: CacheKey<K>, value: String?)

    /**
     * Stores multiple entries in one round-trip.
     */
    suspend fun putBatch(entries: List<Pair<CacheKey<K>, String?>>)

    /**
     * Atomically stores [value] when [key] is absent. The normal cache expiration starts when stored.
     *
     * @return `true` when the value was stored, or `false` when the key already existed
     */
    suspend fun putIfAbsent(key: CacheKey<K>, value: String): Boolean {
        throw UnsupportedOperationException("Atomic put-if-absent is not supported by this cache")
    }

    /**
     * Removes the entry for [key]. When [keyPrefix] is `true`, all entries whose remote key
     * starts with the key's prefix are removed.
     *
     * Exact-key removal is atomic with returning the previous value: backend failures are propagated and a
     * concurrent replacement is not removed. Prefix removal returns no previous value.
     *
     * @return the previously cached value, or `null` if no entry existed
     */
    suspend fun remove(key: CacheKey<K>, keyPrefix: Boolean = false): CacheValue?

    /**
     * Removes every key in [keys] exactly, and every entry whose remote key starts with the prefix of any key in
     * [prefixes], with the same matching as [remove]. Previous values are not returned.
     *
     * Backends override this to apply the whole set in fewer round trips; the default removes one key at a time.
     */
    suspend fun removeBatch(keys: List<CacheKey<K>>, prefixes: List<CacheKey<K>>) {
        keys.forEach { remove(it, keyPrefix = false) }
        prefixes.forEach { remove(it, keyPrefix = true) }
    }

    /** Removes all entries from this cache. */
    suspend fun clear()

    /** Evicts entries that have exceeded their time-to-live. */
    suspend fun evictExpiredItems()

    /** Approximate number of entries currently held in the local tier of this cache. */
    val estimatedSize: Long
}
