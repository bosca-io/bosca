package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.federation.FederationAuth
import bosca.workops.model.federation.FederationConflict
import bosca.workops.model.federation.FederationConflictResolution
import bosca.workops.model.federation.FederationPeer
import bosca.workops.model.federation.FederationPeerKind
import bosca.workops.model.federation.FederationProjectFieldMask
import bosca.workops.model.federation.PrincipalMappingPolicy
import kotlinx.serialization.json.JsonElement

interface FederationPeerService : Service {
    suspend fun list(): List<FederationPeer>
    suspend fun listEnabled(): List<FederationPeer>
    suspend fun getById(id: UUID): FederationPeer?
    suspend fun create(input: CreateFederationPeerInput): FederationPeer
    suspend fun touchSync(id: UUID, etag: String?)
}

data class CreateFederationPeerInput(
    val name: String,
    val description: String?,
    val kind: FederationPeerKind,
    val baseUrl: String,
    val auth: FederationAuth,
    val principalMappingPolicy: PrincipalMappingPolicy,
    val syncIntervalSeconds: Int = 60,
    val enabled: Boolean = true,
)

interface FederationFieldMaskService : Service {
    suspend fun get(projectId: UUID, peerId: UUID): FederationProjectFieldMask?
    suspend fun upsert(projectId: UUID, peerId: UUID, mask: Long): FederationProjectFieldMask
}

interface FederationConflictService : Service {
    suspend fun listForTask(taskId: UUID, offset: Long, limit: Int): List<FederationConflict>
    suspend fun listUnresolved(peerId: UUID, limit: Int): List<FederationConflict>
    suspend fun record(
        taskId: UUID,
        peerId: UUID,
        fieldKey: String,
        oldValue: JsonElement,
        newValue: JsonElement,
        triggeredBy: String,
    ): FederationConflict
    suspend fun resolve(id: UUID, resolution: FederationConflictResolution)
}

interface FederationPrincipalMappingService : Service {
    suspend fun propose(peerId: UUID, remoteUserId: String, proposedProfileId: UUID?, remoteEmail: String?)
    suspend fun accept(peerId: UUID, remoteUserId: String, profileId: UUID)
    suspend fun resolve(peerId: UUID, remoteUserId: String): UUID?
}
