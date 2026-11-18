package bosca.ai.kit.harness

import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.Service

/** Managed child sessions use the existing Kit runtime, scoped authority and durable job lifecycle. */
interface KitDelegationService : Service {
    /** Returns the current managed child choices for the calling runtime. */
    suspend fun settings(authentication: AuthenticationContext, sessionId: UUID): KitDelegationPolicy
    /** Reserves one bounded child once, atomically with its conversation and queued turn. */
    suspend fun start(authentication: AuthenticationContext, sessionId: UUID, input: KitDelegationInput): KitDelegation
    /** Reads a parent-owned result after rechecking the caller and current context access. */
    suspend fun get(authentication: AuthenticationContext, sessionId: UUID, id: UUID): KitDelegation?
    /** Lists bounded parent-owned child tasks, including tasks started by an earlier parent turn. */
    suspend fun list(authentication: AuthenticationContext, sessionId: UUID, offset: Long = 0, limit: Int = 20): List<KitDelegation>
    /** Records a durable wait; the transport may poll without holding a server request or worker. */
    suspend fun wait(authentication: AuthenticationContext, sessionId: UUID, id: UUID): KitDelegation
    /** Records a child-authenticated result proposal; terminal receipt reconciliation confirms its status. */
    suspend fun submit(authentication: AuthenticationContext, sessionId: UUID, result: KitDelegationResult): KitDelegation
    /** Persists cancellation of a parent-owned child and its descendants. */
    suspend fun cancel(authentication: AuthenticationContext, sessionId: UUID, id: UUID): KitDelegation
    /** Resolves the permitted child profile under its saved authority ceiling and current policy. */
    suspend fun resolution(session: KitRuntimeSession): KitHarnessResolution?
    /** Returns the fixed task deadline for child credential, prompt and cleanup reconciliation. */
    suspend fun deadline(sessionId: UUID): OffsetDateTime?
    /** Reports an outstanding child-result wait for the current parent turn. */
    suspend fun waiting(turnId: UUID): Boolean
    /** Confirms child completion from a fenced terminal turn, then republishes the parent's reconciliation. */
    suspend fun finish(session: KitRuntimeSession, turn: KitRuntimeTurn, status: String, summary: String)
    /** Reconciles admission failures and terminal queued turns without submitting native work again. */
    suspend fun reconcile(session: KitRuntimeSession)
    /** Verifies selected input snapshots before a child's first native prompt. */
    suspend fun validateContext(sessionId: UUID, manifest: KitContextManifest)
    /** Cascades an interrupted parent's cancellation through its managed descendants. */
    suspend fun cancelChildren(sessionId: UUID)
    /** Allows parent purge only after every direct child's runtime has completed its own purge. */
    suspend fun childrenStopped(sessionId: UUID): Boolean
}
