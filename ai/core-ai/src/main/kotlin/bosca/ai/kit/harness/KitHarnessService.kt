package bosca.ai.kit.harness

import bosca.ai.chat.model.ChatMessageInput
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.service.Service

/** Durable lifecycle for harness-backed Kit conversations. */
interface KitHarnessService : Service {
    /** Resolves source labels under the current caller and runtime authority; never returns stale stored titles. */
    suspend fun context(authentication: AuthenticationContext, sessionId: UUID): KitContextStatus?
    /** Adds or removes one bounded source selection for the next turn; the caller verifies conversation ownership. */
    suspend fun selectContext(sessionId: UUID, source: KitContextSelection, remove: Boolean)
    /** Clears additional conversation selections, including inaccessible references that have no visible labels. */
    suspend fun clearContext(sessionId: UUID)
    /** Returns the current authorized choices and the settings of the active generation. */
    suspend fun settings(authentication: AuthenticationContext, sessionId: UUID): KitHarnessSettings?
    /** Verifies an explicit override against current Configuration EXECUTE permission and policy revision. */
    suspend fun verifySettings(authentication: AuthenticationContext, sessionId: UUID, choice: KitHarnessChoice?)
    /** Stores a previously authorized choice for the next turn; null restores managed defaults. */
    suspend fun setSettings(sessionId: UUID, choice: KitHarnessChoice?)
    /** Starts the selected runtime before the first prompt; repeated activation joins it. */
    suspend fun activate(authentication: AuthenticationContext, sessionId: UUID)
    /** Atomically accepts the canonical user message and ordered execution intent. */
    suspend fun send(authentication: AuthenticationContext, sessionId: UUID, message: ChatMessageInput)
    /** Exposes pending approval payloads without extending the idle deadline. */
    suspend fun approvals(sessionId: UUID): List<KitRuntimeApproval>
    /** Records one offered decision; decisions are activity and remain durable across Runner loss. */
    suspend fun decide(authentication: AuthenticationContext, sessionId: UUID, approvalId: UUID, optionId: String)
    /** Reports conversation progress without credentials or infrastructure addresses. */
    suspend fun state(sessionId: UUID): KitHarnessStatus?
    /** Cancels accepted pending work; later messages remain queued and native cancellation is reconciled. */
    suspend fun cancel(sessionId: UUID)
    /** Revokes active access and durably requests removal of all runtime generations and archives. */
    suspend fun delete(sessionId: UUID)
    /** Archives an idle runtime before retiring its scoped credential and workload. */
    suspend fun retire(sessionId: UUID)
    /**
     * Returns the destinations the egress proxy may open for the session's current generation: the profile's
     * deployment-approved hosts, its resolved remote MCP endpoints and the administrator's policy hosts.
     * Returns null unless [token] is the live generation's egress token and its credential is unexpired and unrevoked.
     */
    suspend fun egress(sessionId: UUID, token: String): KitEgressDestinations?
}
