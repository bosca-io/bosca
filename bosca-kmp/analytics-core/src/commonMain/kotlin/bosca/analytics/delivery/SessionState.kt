package bosca.analytics.delivery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Tracks inactivity, explicit foreground/background lifecycle, and periodic heartbeats. */
internal class SessionState(
    private val scope: CoroutineScope,
    private val sessionTimeout: Duration = 5.minutes,
    private val onSessionStart: suspend () -> Unit,
    private val onHeartbeat: (suspend () -> Unit)? = null,
    private val heartbeatInterval: Duration = 15.minutes,
    private val onError: (Throwable) -> Unit = {},
) {
    private val mutex = Mutex()
    private var active = false
    private var foreground = true
    private var timeoutJob: Job? = null
    private var heartbeatJob: Job? = null

    fun start() {
        scope.launchHandled { resume() }
    }

    suspend fun onEvent() {
        val shouldStart = mutex.withLock {
            val start = !active
            active = true
            resetTimeoutLocked()
            start
        }
        if (shouldStart) startSessionAndHeartbeat()
    }

    suspend fun pause() {
        mutex.withLock {
            foreground = false
            timeoutJob?.cancel()
            timeoutJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null
        }
    }

    suspend fun resume() {
        val shouldStart = mutex.withLock {
            foreground = true
            val start = !active
            active = true
            resetTimeoutLocked()
            if (!start) startHeartbeatLocked()
            start
        }
        if (shouldStart) startSessionAndHeartbeat()
    }

    suspend fun end() {
        mutex.withLock { endLocked() }
    }

    private fun resetTimeoutLocked() {
        timeoutJob?.cancel()
        timeoutJob = scope.launchHandled {
            delay(sessionTimeout)
            mutex.withLock { endLocked() }
        }
    }

    private suspend fun startSessionAndHeartbeat() {
        try {
            onSessionStart()
        } catch (error: Throwable) {
            mutex.withLock { endLocked() }
            throw error
        }
        mutex.withLock {
            if (active && foreground) startHeartbeatLocked()
        }
    }

    private fun startHeartbeatLocked() {
        val heartbeat = onHeartbeat ?: return
        heartbeatJob?.cancel()
        heartbeatJob = scope.launchHandled {
            heartbeat()
            while (true) {
                delay(heartbeatInterval)
                heartbeat()
            }
        }
    }

    private fun endLocked() {
        active = false
        timeoutJob?.cancel()
        timeoutJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun CoroutineScope.launchHandled(block: suspend CoroutineScope.() -> Unit): Job = launch {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onError(error)
        }
    }
}
