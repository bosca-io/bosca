package bosca.core.platform

/**
 * Platform-specific logging backend.
 *
 * Implementations route log messages to the appropriate platform facility
 * (e.g., Logcat on Android, OSLog on iOS, or console on JVM/JS).
 * Register a concrete instance via [Log.init] to activate logging globally.
 */
interface Logger {

    /**
     * Logs a debug-level message, typically used during development for
     * fine-grained diagnostic output.
     *
     * @param message the debug message to log
     */
    fun d(message: String)

    /**
     * Logs an informational message indicating normal application events
     * (e.g., lifecycle transitions, successful operations).
     *
     * @param message the informational message to log
     */
    fun i(message: String)

    /**
     * Logs a warning-level message for potentially harmful or unexpected
     * situations that do not prevent the application from continuing.
     *
     * @param message the warning message to log
     */
    fun w(message: String)

    /**
     * Logs an error-level message, optionally accompanied by a [Throwable],
     * for failures that require attention.
     *
     * @param message the error message to log
     * @param throwable an optional exception associated with the error
     */
    fun e(message: String, throwable: Throwable? = null)
}

object Log {
    private var logger: Logger? = null

    fun init(logger: Logger) {
        this.logger = logger
    }

    fun d(message: String) = logger?.d(message)
    fun i(message: String) = logger?.i(message)
    fun w(message: String) = logger?.w(message)
    fun e(message: String, throwable: Throwable? = null) = logger?.e(message, throwable)
}
