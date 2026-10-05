package bosca.graphql

/**
 * An exception that carries a stable, machine-readable error [code]. The GraphQL [ExceptionHandler]
 * surfaces it in the error's `extensions.code`, so clients can branch on a code instead of matching
 * human-readable (and easily-reworded) error messages.
 */
interface CodedError {
    val code: String
}

/**
 * Walks this exception's cause chain for a [CodedError] and returns its [CodedError.code], or null if none.
 * Use this instead of a flat `as? CodedError` so the code is still found when the error is wrapped by an
 * intermediate layer — the same contract [ExceptionHandler] uses for `extensions.code`.
 */
fun Throwable.codedErrorCode(): String? {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is CodedError) return cause.code
        cause = cause.cause
    }
    return null
}
