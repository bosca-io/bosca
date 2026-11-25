package bosca.cli.harness.egress

/** The outcome of resolving a runtime's approved destinations. */
internal sealed interface EgressLookupResult {
    /** The session and token are valid; [destinations] are approved. */
    data class Approved(val destinations: Set<EgressDestination>) : EgressLookupResult

    /** The session or token is unknown, inactive, expired or mismatched. */
    data object Denied : EgressLookupResult

    /** Bosca could not answer, for example a network failure or a server error. */
    data object Unavailable : EgressLookupResult
}
