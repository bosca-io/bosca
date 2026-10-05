package bosca.git.service

import bosca.serialization.UUID
import bosca.service.Service

/**
 * Enforces per-profile push rate limiting using a fixed-window counter.
 * Rejects pushes that exceed the configured threshold within the time window
 * to protect the git server from abuse. Counting is distributed, so the limit
 * holds consistently across every git-server pod.
 */
interface PushRateLimiter : Service {

    /**
     * Attempts to acquire a push permit for the given profile. Returns true
     * if the push is allowed, false if the profile has exceeded the rate limit.
     * Always returns true when rate limiting is disabled via configuration.
     */
    suspend fun tryAcquire(profileId: UUID): Boolean
}
