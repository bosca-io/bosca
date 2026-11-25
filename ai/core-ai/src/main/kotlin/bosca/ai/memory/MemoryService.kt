package bosca.ai.memory

import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.service.Service

interface MemoryService : Service {
    /**
     * Saves an explicitly requested memory once per caller/request ID; retries never recreate forgotten content.
     * When the input names a conversation the caller still owns, the returned revision is recorded as read in it.
     */
    suspend fun remember(authentication: AuthenticationContext, input: RememberMemoryInput): Memory

    /**
     * Returns the current memory after entity, collection, source and expiry checks; never a cached snippet.
     * These retrieval checks do not change access to the underlying document through the content APIs.
     */
    suspend fun get(authentication: AuthenticationContext, id: UUID): Memory?

    /**
     * Returns a bounded relevant selection, filtering permissions before bodies or result counts are exposed.
     * [MemoryQuery.offset] and [MemoryQuery.limit] page over visible matches in a stable order; each source is
     * scanned from its start within a fixed candidate window.
     */
    suspend fun search(authentication: AuthenticationContext, query: MemoryQuery): List<Memory>

    /** Updates the current document only if [expectedRevision] still matches, optionally acknowledging the current source. */
    suspend fun update(authentication: AuthenticationContext, id: UUID, expectedRevision: String, statement: String, refreshSource: Boolean): Memory

    /** Soft-deletes the matching memory draft through the content lifecycle; conversation history is independent. */
    suspend fun forget(authentication: AuthenticationContext, id: UUID, expectedRevision: String): Boolean

    /** Records a tool-delivered revision for the caller's conversation without storing its body. */
    suspend fun recordRead(authentication: AuthenticationContext, sessionId: UUID, memory: Memory)

    /** Lists tool-delivered revisions for the caller's conversation, including subsequently unavailable memories. */
    suspend fun reads(authentication: AuthenticationContext, sessionId: UUID): List<MemoryRead>

    /** Clears delivered revisions after the owning runtime has quiesced and discarded its native context. */
    suspend fun clearReads(sessionId: UUID)
}
