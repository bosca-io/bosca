package bosca.core.analytics

/** Consumer contract for the installation identifier owned by Bosca Analytics. */
fun interface InstallationIdProvider {
    /** Returns the Analytics-owned installation identifier. */
    suspend fun getOrCreate(): String
}
