package bosca.pipelines.model

import kotlinx.serialization.Serializable

/**
 * Per-node retry policy. When a node throws (including a timeout), the
 * executor retries it up to [maxAttempts] total, waiting between attempts per the backoff, before the
 * failure propagates (or routes to a wired error port).
 *
 * Backoff before the retry after a failed attempt `n` (1-based) is
 * `min(initialDelaySeconds * multiplier^(n-1), maxDelaySeconds)`:
 *  - [multiplier] `1.0` → a fixed delay of [initialDelaySeconds] between every attempt;
 *  - [multiplier] `> 1.0` → exponential backoff, capped at [maxDelaySeconds].
 *
 * The wait is in-line (it holds the worker), so keep delays modest; long waits belong on a Delay node.
 * Retries re-run the node — only configure them on **idempotent** nodes, or ones whose re-execution is
 * safe (a side-effecting action retried after a partial success can double-apply).
 */
@Serializable
data class RetryPolicy(
    val maxAttempts: Int,
    val initialDelaySeconds: Long = 0,
    val multiplier: Double = 1.0,
    val maxDelaySeconds: Long = 300,
)
