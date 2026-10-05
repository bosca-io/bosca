package bosca.meilisearch.client

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Thrown when a Meilisearch task reaches a terminal failure or cancellation state.
 *
 * @param taskUid the task identifier that failed
 * @param status the terminal status ("failed" or "canceled")
 * @param detail optional detail message from the task error response
 */
class MeilisearchTaskException(
    val taskUid: Int,
    val status: String,
    detail: String? = null,
) : Exception("Meilisearch task $taskUid $status${detail?.let { ": $it" } ?: ""}")

/**
 * Polls the Meilisearch task endpoint until the task reaches a terminal
 * state (succeeded or failed). Uses exponential backoff between polling
 * attempts, starting at [initialDelayMs] and doubling up to [maxDelayMs].
 * Throws [MeilisearchTaskException] if the task fails or is canceled,
 * or if polling exceeds the maximum retry count.
 *
 * @param taskUid the task identifier to poll
 * @param maxRetries maximum number of polling attempts before giving up
 * @param initialDelayMs initial delay between polling attempts in milliseconds
 * @param maxDelayMs maximum delay between polling attempts in milliseconds
 */
suspend fun MeilisearchClient.waitForTask(
    taskUid: Int,
    maxRetries: Int = 200,
    initialDelayMs: Long = 500,
    maxDelayMs: Long = 10_000,
) {
    var currentDelay = initialDelayMs
    repeat(maxRetries) {
        val task = getTask(taskUid)
        when (task.status) {
            "succeeded" -> return
            "failed" -> throw MeilisearchTaskException(taskUid, "failed", task.error?.message)
            "canceled" -> throw MeilisearchTaskException(taskUid, "canceled")
        }
        delay(currentDelay.milliseconds)
        currentDelay = (currentDelay * 2).coerceAtMost(maxDelayMs)
    }
    throw MeilisearchTaskException(taskUid, "timed out", "did not complete within $maxRetries retries")
}
