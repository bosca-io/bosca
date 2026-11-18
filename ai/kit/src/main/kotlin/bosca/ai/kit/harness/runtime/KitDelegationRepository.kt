package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.KitDelegation
import bosca.ai.kit.harness.KitDelegationResult
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface KitDelegationRepository {
    /** Reads an immutable request identity; ownership is checked against its parent runtime. */
    @Query("select * from kit.delegation where id=:id")
    suspend fun get(id: UUID): KitDelegation?
    /** Finds the managed task which owns a child conversation. */
    @Query("select * from kit.delegation where child_session_id=:sessionId")
    suspend fun child(sessionId: UUID): KitDelegation?
    /** Lists direct descendants for cancellation and deletion, including completed children. */
    @Query("select * from kit.delegation where parent_session_id=:sessionId order by created,id")
    suspend fun children(sessionId: UUID): List<KitDelegation>
    /** Pages the parent's durable task history without exposing another conversation's children. */
    @Query("select * from kit.delegation where parent_session_id=:sessionId order by created desc,id limit :limit offset :offset")
    suspend fun list(sessionId: UUID, offset: Long, limit: Int): List<KitDelegation>
    /** Reads all reservations for an initiating turn under its runtime row lock. */
    @Query("select * from kit.delegation where root_turn_id=:turnId order by created,id")
    suspend fun tree(turnId: UUID): List<KitDelegation>
    /** Commits the child identity, input snapshot and bounded reservation before queue publication. */
    @Query("""insert into kit.delegation(id,parent_session_id,parent_turn_id,root_turn_id,child_session_id,child_turn_id,
        depth,input,policy,authority,context_manifest,deadline,created,modified)
        values (:id,:parentSessionId,:parentTurnId,:rootTurnId,:childSessionId,:childTurnId,
        :depth,:input,:policy,:authority,:contextManifest,:deadline,:created,:modified)""", returnUpdateCount = true)
    suspend fun create(delegation: KitDelegation): Int
    /** Marks observed child execution without changing a terminal result. */
    @Query("update kit.delegation set status='RUNNING',modified=now() where id=:id and status='QUEUED'", returnUpdateCount = true)
    suspend fun running(id: UUID): Int
    /** Pins a typed result proposal; a terminal receipt still determines the outcome. */
    @Query("update kit.delegation set result=:result,modified=now() where id=:id and status in ('QUEUED','RUNNING') and result is null", returnUpdateCount = true)
    suspend fun submit(id: UUID, result: KitDelegationResult): Int
    /** Commits the first terminal outcome with its partial output in the same transaction as parent history. */
    @Query("update kit.delegation set status=:status,result=:result,failure=:failure,waiting=false,modified=now() where id=:id and status in ('QUEUED','RUNNING')", returnUpdateCount = true)
    suspend fun finish(id: UUID, status: String, result: KitDelegationResult?, failure: String?): Int
    /** Persists a parent wait without retaining a transaction or server request. */
    @Query("update kit.delegation set waiting=true,wait_turn_id=:turnId,modified=now() where id=:id and status in ('QUEUED','RUNNING')", returnUpdateCount = true)
    suspend fun wait(id: UUID, turnId: UUID): Int
    /** Detects native result waits so the Runner can detach and release its job worker. */
    @Query("select exists(select 1 from kit.delegation where wait_turn_id=:turnId and waiting=true and status in ('QUEUED','RUNNING'))")
    suspend fun waiting(turnId: UUID): Boolean
}
