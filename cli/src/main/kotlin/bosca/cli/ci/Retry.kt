package bosca.cli.ci

import kotlinx.coroutines.delay

suspend fun <T> withRetry(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 1000,
    block: suspend () -> T,
): T {
    var lastException: Exception? = null
    var delayMs = initialDelayMs

    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            lastException = e
            if (attempt < maxAttempts - 1) {
                delay(delayMs)
                delayMs *= 2
            }
        }
    }
    throw lastException!!
}
