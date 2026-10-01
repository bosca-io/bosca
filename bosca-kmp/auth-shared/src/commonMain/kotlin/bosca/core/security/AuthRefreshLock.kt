package bosca.core.security

/**
 * Serializes a token refresh across concurrent execution contexts that share
 * the same persisted tokens. Port of the TS `refreshWithCrossTabLock` seam
 * (which uses the Web Locks API).
 *
 * Only the web target has a genuine multi-context hazard (multiple browser
 * tabs sharing localStorage racing on the single-use refresh token). On
 * single-process targets (Android/iOS/Desktop) this just runs [block]; the
 * token manager's in-process [kotlinx.coroutines.sync.Mutex] coalescing and
 * its adopt-fresh-tokens-from-storage logic provide the remaining safety.
 *
 * @param name a lock name, scoped per API URL so independent backends don't block each other
 */
expect suspend fun <T> withAuthRefreshLock(name: String, block: suspend () -> T): T
