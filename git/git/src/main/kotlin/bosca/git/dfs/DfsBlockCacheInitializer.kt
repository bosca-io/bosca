package bosca.git.dfs

import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig
import org.slf4j.LoggerFactory

/**
 * Configures JGit's global [DfsBlockCache] singleton for S3-backed storage where
 * cache misses trigger HTTP range requests (~50-200ms each). The defaults (32MB
 * limit, 64KB blocks) are tuned for local disk; this reconfigures for network
 * storage with larger blocks and a much larger cache to minimize round-trips.
 */
object DfsBlockCacheInitializer {

    private val log = LoggerFactory.getLogger(DfsBlockCacheInitializer::class.java)

    fun initialize() {
        val config = DfsBlockCacheConfig().apply {
            blockLimit = BLOCK_CACHE_LIMIT
            blockSize = BLOCK_SIZE
        }
        DfsBlockCache.reconfigure(config)
        log.info(
            "DFS block cache configured: {}MB limit, {}KB blocks",
            BLOCK_CACHE_LIMIT / (1024 * 1024),
            BLOCK_SIZE / 1024
        )
    }

    private const val BLOCK_CACHE_LIMIT = 256L * 1024 * 1024
    private const val BLOCK_SIZE = 512 * 1024
}
