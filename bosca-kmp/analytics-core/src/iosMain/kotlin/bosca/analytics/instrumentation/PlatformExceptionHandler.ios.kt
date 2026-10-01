@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package bosca.analytics.instrumentation

import kotlin.native.getUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook

internal actual class PlatformExceptionHandler actual constructor(
    private val onError: (Throwable) -> Unit,
) {
    private var previous: ((Throwable) -> Unit)? = null
    private val handler: (Throwable) -> Unit = { error ->
        try {
            onError(error)
        } finally {
            previous?.invoke(error)
        }
    }

    actual fun install() {
        previous = getUnhandledExceptionHook()
        setUnhandledExceptionHook(handler)
    }

    actual fun uninstall() {
        if (getUnhandledExceptionHook() === handler) {
            setUnhandledExceptionHook(previous)
        }
        previous = null
    }
}
