package bosca.analytics.server

/**
 * Shared helper for computing the sub-millisecond portion of an event
 * timestamp. Matches the convention used by `EventProcessingServiceImpl`
 * (which uses `(Instant.now().nano / 1000) % 1000`) so that events
 * emitted by the server-side client line up with the rest of the
 * pipeline.
 *
 * Extracted to a single function so that
 * [InProcessServerAnalyticsClient], [HttpServerAnalyticsClient], and
 * [ThrowableErrorInfoMapper] don't each keep their own copy of the
 * expression.
 */
internal object SentMicros {
    /** Converts nanoseconds to microseconds, then extracts the
     *  sub-millisecond remainder (0–999) via modulo so the result
     *  augments — but does not duplicate — the millisecond-precision
     *  `sent` timestamp. */
    fun next(): Long = (System.nanoTime() / 1_000) % 1_000
}
