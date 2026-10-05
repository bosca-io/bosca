package bosca.analytics.instrumentation

internal actual class PlatformExceptionHandler actual constructor(
    private val onError: (Throwable) -> Unit,
) {
    private var previous: Thread.UncaughtExceptionHandler? = null
    private val handler = Thread.UncaughtExceptionHandler { thread, error ->
        try {
            onError(error)
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }

    actual fun install() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    actual fun uninstall() {
        if (Thread.getDefaultUncaughtExceptionHandler() === handler) {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        previous = null
    }
}
