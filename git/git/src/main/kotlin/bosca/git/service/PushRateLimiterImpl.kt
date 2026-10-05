package bosca.git.service

import bosca.counter.Counter
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.service.annotation.ServiceImplementation

/**
 * Fixed-window push rate limiter backed by the distributed [Counter], so the
 * limit is enforced consistently across every git-server pod (the previous
 * implementation counted in a per-pod in-memory cache, which drifted under
 * load balancing). Each profile gets one counter bucket per time window; the
 * bucket key carries the window index, so stale windows fall out on their own
 * via the counter backend's retention — there is no cleanup job.
 *
 * All configuration lives under `git.push.rate-limit` and is optional:
 * - `enabled`        — set `false` to disable rate limiting entirely (default `true`)
 * - `max`            — maximum pushes per window per profile (default [DEFAULT_MAX])
 * - `window-seconds` — window length in seconds (default [DEFAULT_WINDOW_SECONDS], one hour)
 *
 * The class is `open` solely so tests can substitute [nowEpochSeconds]; DI
 * constructs it with the [counter] and [application] (the generated provider
 * resolves every constructor parameter, so the clock cannot be a defaulted
 * constructor argument).
 */
@ServiceImplementation
open class PushRateLimiterImpl(
    private val counter: Counter,
    application: BoscaApplication,
) : PushRateLimiter {

    private val config = application.config

    private val enabled: Boolean =
        config.propertyOrNull("git.push.rate-limit.enabled")?.getString()?.toBooleanStrictOrNull() ?: true

    private val maxPerWindow: Long =
        config.propertyOrNull("git.push.rate-limit.max")?.getString()?.toLongOrNull() ?: DEFAULT_MAX

    // Divisor for the window index; coerced to >= 1 so a misconfigured 0 or negative
    // value can never produce a divide-by-zero on the push hot path.
    private val windowSeconds: Long =
        (config.propertyOrNull("git.push.rate-limit.window-seconds")?.getString()?.toLongOrNull()
            ?: DEFAULT_WINDOW_SECONDS).coerceAtLeast(1)

    /** Current wall-clock in epoch seconds. Overridable in tests; never a constructor arg (see class KDoc). */
    protected open fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000

    override suspend fun tryAcquire(profileId: UUID): Boolean {
        if (!enabled) return true
        val window = nowEpochSeconds() / windowSeconds
        val key = "$KEY_PREFIX$profileId.$window"
        return counter.increment(key) <= maxPerWindow
    }

    companion object {
        /** Generous enough for operator submodule sweeps, low enough to bound a runaway push loop. */
        const val DEFAULT_MAX = 10_000L

        /** One hour, matching the limiter's historical window semantics. */
        const val DEFAULT_WINDOW_SECONDS = 3_600L

        private const val KEY_PREFIX = "git.push.rate."
    }
}
