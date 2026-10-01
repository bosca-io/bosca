package bosca.git.dfs

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Threads for blocking JGit work: streamed transfers (each holds one thread while it streams), the
 * LFS upload pipe's writer, and, through [GitWorkDispatcher], everything else.
 *
 * It is not `Dispatchers.IO`, for two reasons:
 * - JGit calls back into [DfsStorageAdapter] and the push hooks, which bridge to coroutines with
 *   `runBlocking`. A `runBlocking` on an IO thread that waits for work dispatched to
 *   `Dispatchers.IO` needs a second IO thread, so enough concurrent JGit calls would leave every
 *   IO thread waiting on work that can never be scheduled.
 * - A transfer's push hooks call services that run on [GitWorkDispatcher]; a bounded pool shared
 *   by both could fill with transfers waiting on work that cannot be scheduled.
 *
 * The pool therefore grows with demand; the transfer routes cap concurrent transfers. Idle threads
 * are kept for [IDLE_KEEP_ALIVE_MINUTES] because `PipedInputStream` and `PipedOutputStream` treat
 * a pipe as broken once the thread that last used its other end has exited. The LFS upload's
 * writer moves to a pool thread for each write, and a slow client can pause it for minutes, so
 * the threads it last used must outlive such a pause.
 */
val GitBlockingDispatcher: CoroutineDispatcher = ThreadPoolExecutor(
    0,
    Int.MAX_VALUE,
    IDLE_KEEP_ALIVE_MINUTES,
    TimeUnit.MINUTES,
    SynchronousQueue(),
    Thread.ofPlatform().name("bosca-git-", 0).daemon(true).factory(),
).asCoroutineDispatcher()

/**
 * [GitBlockingDispatcher] limited to [MAX_CONCURRENT_GIT_WORK] threads, for blocking JGit work that
 * serves one request or job and returns: browsing, diffs, commits, merges, maintenance. Streamed
 * transfers stay on [GitBlockingDispatcher], bounded by their own permits, so neither can starve
 * the other. Work here may suspend on anything, but must never block its thread waiting on a transfer.
 */
val GitWorkDispatcher: CoroutineDispatcher = GitBlockingDispatcher.limitedParallelism(MAX_CONCURRENT_GIT_WORK)

/** Concurrent blocking JGit calls outside streamed transfers; later calls wait without holding a thread. */
private const val MAX_CONCURRENT_GIT_WORK = 256

/**
 * Longer than any pause a live transfer can take: a stalled peer is disconnected by the server's
 * idle timeout (`bosca.server.idle-timeout-seconds`, 5 minutes by default), so a pipe's threads
 * outlive every pause short of that. Keep this above the idle timeout if it is raised.
 */
private const val IDLE_KEEP_ALIVE_MINUTES = 10L
