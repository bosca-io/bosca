package bosca.server.netty

/**
 * A request executor that the [BlockedThreadWatchdog] and the management listener's liveness check
 * send heartbeats to: an I/O event loop, or the request pool in [RequestDispatcherMode.POOL] mode.
 *
 * [submit] queues a heartbeat on it. [threads] returns the threads to dump when it stalls, given
 * the thread that last ran a heartbeat (null before the first).
 */
internal class WatchedExecutor(
    val name: String,
    val submit: (Runnable) -> Unit,
    val threads: (lastHeartbeatThread: Thread?) -> Collection<Thread>,
) {
    /** Describes a stall of [stalledMillis] against [thresholdMillis], with the stack traces of the threads involved. */
    fun describeStall(stalledMillis: Long, thresholdMillis: Long, lastHeartbeatThread: Thread?): String = buildString {
        append("Request executor ").append(name)
        append(" has not run a task for ").append(stalledMillis).append(" ms (threshold ")
        append(thresholdMillis).append(" ms); a handler is blocking or computing on it.")
        for (thread in threads(lastHeartbeatThread)) {
            append("\n\"").append(thread.name).append("\" ").append(thread.state)
            for (frame in thread.stackTrace) append("\n    at ").append(frame)
        }
    }
}
