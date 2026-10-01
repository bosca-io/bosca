package bosca.cdn

/**
 * Manages Content Delivery Network (CDN) operations such as cache invalidation.
 *
 * Implementations interact with the configured CDN provider to purge cached
 * content when underlying resources are updated.
 */
interface CdnManager {
    /**
     * Purges all cached content from the CDN.
     *
     * @return `true` if the cache was successfully cleared, `false` if the operation failed
     */
    suspend fun clearCache(): Boolean
}
