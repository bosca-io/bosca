package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.KitRuntimeSession
import bosca.ai.kit.harness.KitRuntimeTurn
import bosca.ai.kit.harness.KitRuntimeArchive
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface KitRuntimeRepository {
    /** Current dispatched workload count, including pending removal, across principals and profiles. */
    @Query("select count(*) from kit.runtime_session where dispatch_id is not null and state <> 'FAILED'")
    suspend fun activeCount(): Long

    /** Current visible failed sessions, including admission failures; deleted tombstones are excluded. */
    @Query("select count(*) from kit.runtime_session where state = 'FAILED' and deleted_at is null")
    suspend fun failedCount(): Long
    /** Serializes admission and archive reservations across Runner instances until transaction commit. */
    @Query("select true from pg_advisory_xact_lock(hashtextextended('kit.runtime.admission', 0))")
    suspend fun lockAdmission(): Boolean

    /** Checks committed reservations after taking the admission lock; failed generations have confirmed removal. */
    @Query("""select count(*) < :maxRuntimes and count(*) filter (where principal_id=:principalId) < :maxRuntimesPerPrincipal
        from kit.runtime_session where dispatch_id is not null and state <> 'FAILED'""")
    suspend fun hasCapacity(principalId: UUID, maxRuntimes: Int, maxRuntimesPerPrincipal: Int): Boolean

    /** Reserved object bytes, including uncommitted uploads and pending deletion. Read under the admission lock. */
    @Query("select coalesce(sum(a.bytes),0)::bigint from kit.runtime_archive a join kit.runtime_session s on s.session_id=a.session_id where s.principal_id=:principalId")
    suspend fun archiveBytes(principalId: UUID): Long

    /** Registers an object before upload, once per immutable generation. */
    @Query("insert into kit.runtime_archive (key,session_id,generation,bytes,created) values (:key,:sessionId,:generation,:bytes,:created) on conflict do nothing", returnUpdateCount = true)
    suspend fun reserveArchive(archive: KitRuntimeArchive): Int

    /** Reads one generation's existing upload reservation. */
    @Query("select * from kit.runtime_archive where key=:key")
    suspend fun archive(key: String): KitRuntimeArchive?

    /** Lists owned objects for deletion, independently of the current checkpoint pointer. */
    @Query("select * from kit.runtime_archive where session_id=:sessionId order by generation")
    suspend fun archives(sessionId: UUID): List<KitRuntimeArchive>

    /** Superseded objects exclude the current pointer and an archive upload still owned by the stopping generation. */
    @Query("""select a.* from kit.runtime_archive a join kit.runtime_session s on s.session_id=a.session_id
        where a.session_id=:sessionId and a.key is distinct from s.archive_key
        and not (a.generation=s.generation and s.state='STOPPING' and s.dispatch_id is not null) order by a.generation""")
    suspend fun obsoleteArchives(sessionId: UUID): List<KitRuntimeArchive>

    /** Releases quota only after object deletion, and never removes a live checkpoint's reservation. */
    @Query("""delete from kit.runtime_archive a where a.key=:key and a.session_id=:sessionId
        and exists (select 1 from kit.runtime_session s where s.session_id=a.session_id and (s.deleted_at is not null or s.archive_key is distinct from a.key))""", returnUpdateCount = true)
    suspend fun deleteArchive(sessionId: UUID, key: String): Int

    /** Removes decisions after workload and archive deletion. */
    @Query("delete from kit.runtime_approval where session_id=:sessionId")
    suspend fun deleteApprovals(sessionId: UUID)

    /** Removes execution intents after workload and archive deletion. */
    @Query("delete from kit.runtime_turn where session_id=:sessionId")
    suspend fun deleteTurns(sessionId: UUID)

    /** Removes a fenced tombstone after all external cleanup has succeeded. */
    @Query("delete from kit.runtime_session where session_id=:sessionId and owner=:owner and epoch=:epoch and deleted_at is not null", returnUpdateCount = true)
    suspend fun deleteSession(sessionId: UUID, owner: UUID, epoch: Long): Int

    /** An unused conversation has no native prompt state to resume after a cold start. */
    @Query("select exists(select 1 from kit.runtime_turn where session_id=:sessionId and status <> 'QUEUED')")
    suspend fun hasNativeTurns(sessionId: UUID): Boolean

    /** Terminal turns cannot leave an approval that inhibits later idle archival. */
    @Query("update kit.runtime_approval set decision='cancelled' where turn_id=:turnId and decision is null")
    suspend fun closeApprovals(turnId: UUID)

    /** Saves an approval once per native permission request. */
    @Query("insert into kit.runtime_approval (id, session_id, turn_id, request_id, request, expires, created) values (:id,:sessionId,:turnId,:requestId,:request,:expires,:created) on conflict (turn_id,request_id) do nothing")
    suspend fun addApproval(approval: bosca.ai.kit.harness.KitRuntimeApproval)

    /** Reads pending approval decisions separately from idle activity. */
    @Query("select * from kit.runtime_approval where session_id=:sessionId and decision is null order by expires")
    suspend fun approvals(sessionId: UUID): List<bosca.ai.kit.harness.KitRuntimeApproval>

    /** Reads one durable native request including its eventual decision. */
    @Query("select * from kit.runtime_approval where turn_id=:turnId and request_id=:requestId")
    suspend fun approval(turnId: UUID, requestId: String): bosca.ai.kit.harness.KitRuntimeApproval?

    /** Applies the first decision only. */
    @Query("update kit.runtime_approval set decision=:decision where id=:id and decision is null", returnUpdateCount = true)
    suspend fun decide(id: UUID, decision: String): Int

    /** Creates the runtime identity once; concurrent activation joins the same record. */
    @Query("insert into kit.runtime_session (session_id, principal_id, configuration, authority, resolved_settings, idle_at) values (:sessionId, :principalId, :configuration, :authority, :resolvedSettings, :idleAt) on conflict do nothing")
    suspend fun create(session: KitRuntimeSession)

    /** Reads durable runtime identity without extending its idle deadline. */
    @Query("select * from kit.runtime_session where session_id = :sessionId")
    suspend fun get(sessionId: UUID): KitRuntimeSession?

    /** Serializes accepted activity, turn claims, archival claims and generation changes. */
    @Query("select * from kit.runtime_session where session_id = :sessionId for update")
    suspend fun lock(sessionId: UUID): KitRuntimeSession?

    /** Commits only the current optimistic version. */
    @Query("""update kit.runtime_session set configuration=:configuration, requested_settings=:requestedSettings, resolved_settings=:resolvedSettings, restore_workspace_only=:restoreWorkspaceOnly,
        context_manifest=:contextManifest, context_reset=:contextReset, state=:state, generation=:generation, epoch=:epoch, needs_context=:needsContext, owner=:owner,
        lease_until=:leaseUntil, dispatch_id=:dispatchId, endpoint=:endpoint, runtime_instance_id=:runtimeInstanceId, secrets=:secrets, credential_id=:credentialId,
        native_session_id=:nativeSessionId, active_turn=:activeTurn, cancel_requested_at=:cancelRequestedAt, idle_at=:idleAt,
        archive_key=:archiveKey, archive_sha256=:archiveSha256, archive_size=:archiveSize, archive_created=:archiveCreated, archive_failure=:archiveFailure, failure=:failure, deleted_at=:deletedAt,
        modified=now(), version=version+1 where session_id=:sessionId and version=:version""", returnUpdateCount = true)
    suspend fun save(session: KitRuntimeSession): Int

    /** Ordered pending messages, independently durable through runtime archival and removal. */
    @Query("select * from kit.runtime_turn where session_id=:sessionId and status='QUEUED' order by sequence limit 1")
    suspend fun next(sessionId: UUID): KitRuntimeTurn?

    /** Cancels only messages accepted before the caller's session-row lock, leaving the active turn to its owner. */
    @Query("""update kit.runtime_turn set status='CANCELLED' where session_id=:sessionId and status='QUEUED'
        and id is distinct from :activeTurn returning *""")
    suspend fun cancelQueued(sessionId: UUID, activeTurn: UUID?): List<KitRuntimeTurn>

    /** Persists an accepted message intent before queue publication. */
    @Query("insert into kit.runtime_turn (id, session_id, message, created) values (:id, :sessionId, :message, :created)")
    suspend fun add(turn: KitRuntimeTurn)

    /** Reads the immutable prompt identity for reconciliation. */
    @Query("select * from kit.runtime_turn where id=:id")
    suspend fun turn(id: UUID): KitRuntimeTurn?

    /** Pins the resolved policy once, before an active turn can be submitted. */
    @Query("update kit.runtime_turn set resolved_settings=:settings where id=:id and status='QUEUED' and resolved_settings is null", returnUpdateCount = true)
    suspend fun resolve(id: UUID, settings: JsonElement): Int

    /** Pins the exact handoff before submitting a prompt; only an unsent turn can receive context. */
    @Query("update kit.runtime_turn set context_manifest=:manifest where id=:id and status='QUEUED'", returnUpdateCount = true)
    suspend fun context(id: UUID, manifest: JsonElement): Int

    /** Marks an unsent turn and fixes its deadline before any native prompt side effect. */
    @Query("update kit.runtime_turn set status='SENT', deadline=:deadline where id=:id and status='QUEUED'", returnUpdateCount = true)
    suspend fun send(id: UUID, deadline: bosca.serialization.OffsetDateTime): Int

    /** Records terminal outcomes; a terminal turn is never eligible for re-execution. */
    @Query("update kit.runtime_turn set status=:status, result=:result where id=:id and status in ('QUEUED','SENT')", returnUpdateCount = true)
    suspend fun finish(id: UUID, status: String, result: JsonElement?): Int

    /** Renews current ownership; an expired owner cannot resurrect its lease. */
    @Query("update kit.runtime_session set lease_until=now()+interval '60 seconds' where session_id=:sessionId and owner=:owner and epoch=:epoch and lease_until>now() and deleted_at is null", returnUpdateCount = true)
    suspend fun renew(sessionId: UUID, owner: UUID, epoch: Long): Int

    /**
     * Claims a bounded batch for recovery, atomically so Runner replicas do not republish a session together.
     * Unpublished intents, expired idle deadlines and interrupted transitions are claimed every [urgentSeconds].
     * A healthy idle session has no durable signal for runtime retirement or a policy change, so it is claimed
     * every [idleSeconds] to observe them.
     */
    @Query("""update kit.runtime_session target set recovered=now() where target.session_id in (
        select s.session_id from kit.runtime_session s where (s.lease_until is null or s.lease_until<now()) and (
            ((s.deleted_at is not null or s.state in ('STARTING','BUSY','WAITING_FOR_APPROVAL','STOPPING') or (s.state='READY' and s.idle_at<now())
                or (s.state<>'FAILED' and exists (select 1 from kit.runtime_turn t where t.session_id=s.session_id and t.status='QUEUED'))
                or exists (select 1 from kit.runtime_archive a where a.session_id=s.session_id and a.key is distinct from s.archive_key
                    and not (a.generation=s.generation and s.state='STOPPING' and s.dispatch_id is not null))
                or (s.dispatch_id is null and s.archive_key is not null
                    and (s.configuration->>'maxArchiveAgeSeconds')::bigint > 0
                    and (s.archive_created is null or s.archive_created + (s.configuration->>'maxArchiveAgeSeconds')::double precision * interval '1 second' < now())))
                and (s.recovered is null or s.recovered <= now() - (:urgentSeconds)::double precision * interval '1 second'))
            or (s.state='READY' and (s.recovered is null or s.recovered <= now() - (:idleSeconds)::double precision * interval '1 second')))
        order by s.recovered nulls first, s.modified limit :limit for update of s skip locked) returning *""")
    suspend fun pending(limit: Int, urgentSeconds: Long, idleSeconds: Long): List<KitRuntimeSession>
}
