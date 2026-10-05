package bosca.security.service

import bosca.cache.Cache
import bosca.cache.StringCacheKey
import kotlinx.serialization.json.Json

/**
 * Cache-backed, single-use, short-lived store for account-linking state.
 *
 * Two kinds of entries share one cache, distinguished by key prefix:
 * - `pl:<token>` → a [PendingLink] (the credential to attach + the target account). The token is
 *   handed to the browser when a collision is detected.
 * - `ep:<emailToken>` → the `pl:` token it confirms. The `emailToken` is delivered ONLY by email, so
 *   possessing it proves control of the account's verified email (the magic-link proof).
 *
 * Entries expire with the cache's TTL; consuming an entry removes it so it cannot be replayed.
 */
class PendingLinkStore(
    private val cache: Cache<String>,
    private val json: Json,
) {

    private fun pendingKey(token: String) = StringCacheKey(CACHE_NAME, "pl:$token")
    private fun emailProofKey(emailToken: String) = StringCacheKey(CACHE_NAME, "ep:$emailToken")

    suspend fun putPendingLink(token: String, link: PendingLink) {
        cache.put(pendingKey(token), json.encodeToString(link))
    }

    /** Reads the pending link without removing it (proof attempts may retry). */
    suspend fun peekPendingLink(token: String): PendingLink? {
        val value = cache.get(pendingKey(token))
        if (!value.exists) return null
        return value.value?.let { json.decodeFromString<PendingLink>(it) }
    }

    /** Atomically removes and returns the pending link, so it can only be completed once. */
    suspend fun consumePendingLink(token: String): PendingLink? {
        val value = cache.remove(pendingKey(token))
        return value?.value?.let { json.decodeFromString<PendingLink>(it) }
    }

    suspend fun putEmailProof(emailToken: String, linkToken: String) {
        cache.put(emailProofKey(emailToken), linkToken)
    }

    /** Atomically removes and returns the `pl:` token an email-proof token maps to. */
    suspend fun consumeEmailProof(emailToken: String): String? {
        return cache.remove(emailProofKey(emailToken))?.value
    }

    companion object {
        const val CACHE_NAME = "security:link:pending"
    }
}
