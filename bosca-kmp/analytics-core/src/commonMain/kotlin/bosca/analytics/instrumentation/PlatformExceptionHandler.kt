package bosca.analytics.instrumentation

internal expect class PlatformExceptionHandler(onError: (Throwable) -> Unit) {
    fun install()
    fun uninstall()
}
