package bosca.git.transport

import bosca.git.dfs.GitBlockingDispatcher
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** Read size for streaming an object from storage to the HTTP response. */
internal const val GIT_TRANSFER_COPY_BUFFER_SIZE = 64 * 1024

/**
 * The most streamed git transfers (ref advertisements, clones/fetches, pushes, LFS uploads) that
 * run at once. Each holds a thread of [GitBlockingDispatcher]; later transfers wait for a permit
 * without holding a thread. A push takes its permit only once it holds the repository write lock, so
 * pushes queued behind GC or another push never hold permits that clones need.
 */
internal const val MAX_CONCURRENT_GIT_TRANSFERS = 256

/**
 * The longest a clone, fetch or push may stream. Far longer than the default 5-minute streaming
 * limit, which large transfers legitimately exceed, but finite: the connection's idle timeout does
 * not end a peer that trickles a byte every few minutes, and such a transfer would otherwise hold
 * its permit (and, for a push, the repository write lock) indefinitely. Reaching it is reported as
 * a failure: a push may have updated refs before its report could be sent.
 */
internal val GIT_TRANSFER_TIME_LIMIT = 1.hours

/** Admits streamed git transfers; see [MAX_CONCURRENT_GIT_TRANSFERS]. */
internal val gitTransferPermits = Semaphore(MAX_CONCURRENT_GIT_TRANSFERS)

/**
 * Runs one streamed git exchange: [serve] (JGit's upload-pack or receive-pack) reads the request
 * body and writes the response directly, with no whole-body buffering.
 *
 * [serve] blocks, so it runs on [GitBlockingDispatcher], and its streams block that thread while
 * the body has no data yet or the client is not reading. Cancellation (the client disconnecting,
 * or [GIT_TRANSFER_TIME_LIMIT]) fails the waiting read or write, so JGit stops.
 *
 * The limit is a [withTimeout] rather than the response's own streaming limit, which ends a
 * response quietly (it is how watch feeds end): an expired transfer must be reported. It runs on
 * [GitBlockingDispatcher], so it is real time whatever the caller's dispatcher; [timeLimit] is
 * only ever shortened by tests.
 */
internal suspend fun streamGitExchange(
    call: ServerCall,
    contentType: ContentType,
    timeLimit: Duration = GIT_TRANSFER_TIME_LIMIT,
    serve: (requestBody: InputStream, response: OutputStream) -> Unit,
) {
    call.respondStreaming(contentType, HttpStatusCode.OK, timeLimit = null) { stream ->
        withContext(GitBlockingDispatcher) {
            withTimeout(timeLimit) {
                call.request.bodyInputStream().use { requestBody ->
                    serve(requestBody, stream.outputStream())
                }
            }
        }
    }
}
