package bosca.server.middleware

import bosca.counter.Counter
import bosca.di.provide
import bosca.server.ServerCall

/**
 * Counts every HTTP response by status class into the distributed [Counter] — one key per
 * `(service, status class, minute)`: `http.<service>.<2xx|4xx|5xx>.<epoch-minute>`. Reading a window of
 * those buckets yields an API's response-code rate (error rate = `(4xx + 5xx) / total`) without a
 * per-request analytics row or a rollup job — the bucket lives in the key and the backend expires it.
 *
 * Runs after each call; the [filter] excludes health-check probes, the same way request logging does.
 */
class ResponseCounterMiddleware(
    private val serviceName: String,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val filter: (ServerCall) -> Boolean = { true },
) : CallMiddleware {

    // Resolved on first use (the Counter provider is registered after this middleware is installed) and
    // cached; a concurrent first-resolve just returns the same singleton, so no lock is needed.
    @Volatile
    private var counter: Counter? = null

    override suspend fun afterCall(call: ServerCall) {
        if (!filter(call)) return
        val status = call.response.status()?.value ?: 200
        val minute = nowEpochSeconds() / 60
        resolveCounter().increment("$KEY_PREFIX.$serviceName.${status / 100}xx.$minute")
    }

    private suspend fun resolveCounter(): Counter =
        counter ?: provide<Counter>().also { counter = it }

    companion object {
        const val KEY_PREFIX = "http"

        /** The counter key for a service's response-class bucket at a given epoch-minute. */
        fun key(service: String, statusClass: String, minute: Long): String =
            "$KEY_PREFIX.$service.$statusClass.$minute"
    }
}
