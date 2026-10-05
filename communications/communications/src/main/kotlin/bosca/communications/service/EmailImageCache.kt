package bosca.communications.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine

/**
 * Fetch-once cache for `bml-inline` email images, holding the FINAL attachment form — the
 * immutable [RenderedEmailImage] with its base64 content — so every render of the same image
 * shares one instance. Caching anything earlier (raw bytes) would still pay a fresh base64
 * copy per send; caching the finished value makes a render's per-image cost a reference.
 *
 * Keyed `(project, version, source)`: published versions never change, so the whole value —
 * cid, media type, filename, bytes — is deterministic per key and entries never invalidate.
 * Caffeine's weigher bounds the cache by content size, evicting by frequency.
 */
internal class EmailImageCache(maxWeight: Long = 32L * 1024 * 1024) {

    private val cache: Cache<String, RenderedEmailImage> = Caffeine.newBuilder()
        .maximumWeight(maxWeight)
        .weigher { _: String, image: RenderedEmailImage -> image.contentBase64.length }
        .build()

    suspend fun get(key: String, fetch: suspend () -> RenderedEmailImage): RenderedEmailImage =
        // A racing duplicate fetch is harmless — deterministic value, last put wins with an
        // equal instance — and keeps the suspend fetch outside any cache-internal locking.
        cache.getIfPresent(key) ?: fetch().also { cache.put(key, it) }
}
