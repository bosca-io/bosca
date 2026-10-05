package bosca.analytics.delivery

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionStateTest {
    @Test
    fun `an event after inactivity starts a new session`() = runTest {
        var starts = 0
        val state = SessionState(
            scope = this,
            sessionTimeout = 1.seconds,
            onSessionStart = { starts++ },
        )

        state.start()
        runCurrent()
        assertEquals(1, starts)

        advanceTimeBy(1_001)
        runCurrent()
        state.onEvent()
        assertEquals(2, starts)
    }

    @Test
    fun `pause stops heartbeats and resume emits one immediately`() = runTest {
        var heartbeats = 0
        val state = SessionState(
            scope = this,
            sessionTimeout = 30.seconds,
            heartbeatInterval = 5.seconds,
            onSessionStart = {},
            onHeartbeat = { heartbeats++ },
        )

        state.start()
        runCurrent()
        assertEquals(1, heartbeats)
        state.pause()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, heartbeats)
        state.resume()
        runCurrent()
        assertEquals(2, heartbeats)
    }

    @Test
    fun `session and heartbeat failures are reported and leave lifecycle recoverable`() = runTest {
        val errors = mutableListOf<Throwable>()
        var failStart = true
        val state = SessionState(
            scope = this,
            sessionTimeout = 30.seconds,
            heartbeatInterval = 1.seconds,
            onSessionStart = {
                if (failStart) {
                    failStart = false
                    error("start failed")
                }
            },
            onHeartbeat = { error("heartbeat failed") },
            onError = { errors += it },
        )

        state.start()
        runCurrent()
        assertTrue(errors.any { it.message == "start failed" })
        state.onEvent()
        runCurrent()
        assertTrue(errors.any { it.message == "heartbeat failed" })
        state.end()
    }

    @Test
    fun `session start rechecks lifecycle changed by the start callback`() = runTest {
        var pausedHeartbeats = 0
        lateinit var paused: SessionState
        paused = SessionState(
            scope = this,
            onSessionStart = { paused.pause() },
            onHeartbeat = { pausedHeartbeats++ },
        )
        paused.onEvent()
        runCurrent()
        assertEquals(0, pausedHeartbeats)

        var endedHeartbeats = 0
        lateinit var ended: SessionState
        ended = SessionState(
            scope = this,
            onSessionStart = { ended.end() },
            onHeartbeat = { endedHeartbeats++ },
        )
        ended.onEvent()
        runCurrent()
        assertEquals(0, endedHeartbeats)
    }
}
