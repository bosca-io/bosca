package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.federation.FederationConflict
import bosca.workops.model.federation.FederationPeer
import bosca.workops.model.federation.FederationProjectFieldMask

@Repository
interface FederationPeerRepository {

    @Query("select * from workops.federation_peer where id = :id")
    suspend fun getById(id: UUID): FederationPeer?

    @Query("select * from workops.federation_peer where enabled = true order by name")
    suspend fun listEnabled(): List<FederationPeer>

    @Query("select * from workops.federation_peer order by name")
    suspend fun listAll(): List<FederationPeer>

    @Query(
        """
        insert into workops.federation_peer
            (name, description, kind, base_url, auth_payload,
             principal_mapping_policy, sync_interval_seconds, enabled)
        values
            (:name, :description, :kind, :baseUrl, cast(:authPayload as jsonb),
             :principalMappingPolicy, :syncIntervalSeconds, :enabled)
        returning *
        """
    )
    suspend fun add(input: FederationPeerInsertParams): FederationPeer

    @Query(
        """
        update workops.federation_peer
        set last_sync_at = now(), last_sync_etag = :etag, version = version + 1
        where id = :id
        """
    )
    suspend fun touchSync(id: UUID, etag: String?)
}

data class FederationPeerInsertParams(
    val name: String,
    val description: String?,
    val kind: String,
    val baseUrl: String,
    val authPayload: String,
    val principalMappingPolicy: String,
    val syncIntervalSeconds: Int,
    val enabled: Boolean,
)

@Repository
interface FederationFieldMaskRepository {

    @Query(
        """
        select * from workops.federation_field_mask
        where project_id = :projectId and peer_id = :peerId
        """
    )
    suspend fun get(projectId: UUID, peerId: UUID): FederationProjectFieldMask?

    @Query(
        """
        insert into workops.federation_field_mask (project_id, peer_id, mask)
        values (:projectId, :peerId, :mask)
        on conflict (project_id, peer_id) do update set mask = excluded.mask
        returning *
        """
    )
    suspend fun upsert(projectId: UUID, peerId: UUID, mask: Long): FederationProjectFieldMask
}

@Repository
interface FederationConflictRepository {

    @Query(
        """
        insert into workops.federation_conflict
            (task_id, peer_id, field_key, old_value, new_value, triggered_by)
        values
            (:taskId, :peerId, :fieldKey, cast(:oldValue as jsonb),
             cast(:newValue as jsonb), :triggeredBy)
        returning *
        """
    )
    suspend fun add(input: FederationConflictInsertParams): FederationConflict

    @Query(
        """
        select * from workops.federation_conflict
        where task_id = :taskId
        order by created_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listForTask(taskId: UUID, offset: Long, limit: Int): List<FederationConflict>

    @Query(
        """
        select * from workops.federation_conflict
        where peer_id = :peerId and resolution = 'UNRESOLVED'
        order by created_at desc
        limit :limit
        """
    )
    suspend fun listUnresolved(peerId: UUID, limit: Int): List<FederationConflict>

    @Query(
        """
        update workops.federation_conflict
        set resolution = :resolution, resolved_at = now()
        where id = :id and resolution = 'UNRESOLVED'
        """
    )
    suspend fun resolve(id: UUID, resolution: String)
}

data class FederationConflictInsertParams(
    val taskId: UUID,
    val peerId: UUID,
    val fieldKey: String,
    val oldValue: String,
    val newValue: String,
    val triggeredBy: String,
)

@Repository
interface FederationPrincipalMappingRepository {

    @Query(
        """
        insert into workops.federation_principal_mapping_proposal
            (peer_id, remote_user_id, proposed_profile_id, remote_email)
        values (:peerId, :remoteUserId, :proposedProfileId, :remoteEmail)
        on conflict (peer_id, remote_user_id) do update set
            proposed_profile_id = excluded.proposed_profile_id,
            remote_email = excluded.remote_email
        """
    )
    suspend fun propose(
        peerId: UUID,
        remoteUserId: String,
        proposedProfileId: UUID?,
        remoteEmail: String?,
    )

    @Query(
        """
        update workops.federation_principal_mapping_proposal
        set proposed_profile_id = :profileId, accepted_at = now()
        where peer_id = :peerId and remote_user_id = :remoteUserId
        """
    )
    suspend fun accept(peerId: UUID, remoteUserId: String, profileId: UUID)

    @Query(
        """
        select proposed_profile_id from workops.federation_principal_mapping_proposal
        where peer_id = :peerId and remote_user_id = :remoteUserId and accepted_at is not null
        """
    )
    suspend fun resolve(peerId: UUID, remoteUserId: String): UUID?
}
