package bosca.localization.sync

/**
 * Type-safe wrapper around the set of registered [SyncProvider] implementations,
 * avoiding the type-erasure issues that arise when registering `List<SyncProvider>`
 * directly with the DI system.
 */
class SyncProviders(val providers: List<SyncProvider>)
