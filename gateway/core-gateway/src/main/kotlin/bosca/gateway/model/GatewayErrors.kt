package bosca.gateway.model

import bosca.serialization.UUID

/**
 * Common base for typed Gateway failures. Every mutation must surface a
 * typed exception rather than a free-form [IllegalStateException]; this
 * hierarchy is the load-bearing piece.
 */
sealed class GatewayException(message: String) : RuntimeException(message)

/** The targeted entity was not found (or was soft-deleted on a path that requires an active row). */
class GatewayNotFoundException(val type: String, val handle: String) :
    GatewayException("$type not found: $handle")

/**
 * Optimistic-lock mismatch — the client's `expectedVersion` did not match
 * the persisted row. The client must reload and retry.
 */
class GatewayOptimisticLockFailedException(val type: String, val id: UUID) :
    GatewayException("Optimistic lock failed: $type $id")

/** A unique-constraint conflict — duplicate name, etc. */
class GatewayConflictException(val type: String, val handle: String) :
    GatewayException("$type already exists: $handle")

/**
 * The entity cannot be deleted because another entity references it
 * (e.g. routes still pointing at a Gateway).
 */
class GatewayInUseException(val type: String, val id: UUID, val reason: String) :
    GatewayException("$type $id cannot be deleted: $reason")

/** A persisted invariant — formatting, ordering, hierarchy violation — was rejected. */
class GatewayValidationException(val field: String, val reason: String) :
    GatewayException("Invalid $field: $reason")

/**
 * Returns true if [throwable] (or any of its causes) is a PostgreSQL
 * unique-constraint violation (SQLSTATE `23505`). Service-layer code
 * remaps these to [GatewayConflictException] so the typed-error
 * contract holds even when a TOCTOU race lets two creates reach the
 * insert at the same time.
 */
fun isUniqueViolation(throwable: Throwable): Boolean {
    var cause: Throwable? = throwable
    while (cause != null) {
        if (cause is java.sql.SQLException && cause.sqlState == "23505") return true
        cause = cause.cause
    }
    return false
}
