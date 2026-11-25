package bosca.cli.harness.egress

/** Resolves which destinations a runtime may reach through the egress proxy. */
internal fun interface EgressLookup {
    /** Returns the destinations approved for [credentials], or why none are available. */
    suspend fun lookup(credentials: EgressCredentials): EgressLookupResult
}
