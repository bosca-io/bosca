package bosca.bml.message.host

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A cross-classloader rendezvous for the hot-swap test: the gated template's server script calls
 * [enter] (resolved from the PARENT classloader — the same sharing contract core-bml relies on),
 * which parks the render mid-flight until the test calls [open]. This is how the test holds a
 * render in flight on the old version while a swap activates the new one.
 */
object SwapGate {
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)

    @JvmStatic
    fun enter(): String {
        entered.countDown()
        check(release.await(30, TimeUnit.SECONDS)) { "SwapGate was never opened" }
        return "gated"
    }

    fun awaitEntered() {
        check(entered.await(30, TimeUnit.SECONDS)) { "the gated render never started" }
    }

    fun open() {
        release.countDown()
    }
}
