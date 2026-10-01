package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.PendingPhaseImplementationException
import bosca.workops.model.federation.FederationAuth
import bosca.workops.model.federation.FederationConflict
import bosca.workops.model.federation.FederationConflictResolution
import bosca.workops.model.federation.FederationPeer
import bosca.workops.model.federation.FederationPeerKind
import bosca.workops.model.federation.FederationProjectFieldMask
import bosca.workops.model.federation.RemoteTaskSnapshot
import bosca.workops.repository.FederationConflictInsertParams
import bosca.workops.repository.FederationConflictRepository
import bosca.workops.repository.FederationFieldMaskRepository
import bosca.workops.repository.FederationPeerInsertParams
import bosca.workops.repository.FederationPeerRepository
import bosca.workops.repository.FederationPrincipalMappingRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@ServiceImplementation
class FederationPeerServiceImpl(
    private val repository: FederationPeerRepository,
    private val json: Json,
) : FederationPeerService {

    override suspend fun list() = repository.listAll()
    override suspend fun listEnabled() = repository.listEnabled()
    override suspend fun getById(id: UUID) = repository.getById(id)

    override suspend fun create(input: CreateFederationPeerInput): FederationPeer = repository.add(
        FederationPeerInsertParams(
            name = input.name, description = input.description,
            kind = input.kind.name, baseUrl = input.baseUrl,
            authPayload = json.encodeToString(FederationAuth.serializer(), input.auth),
            principalMappingPolicy = input.principalMappingPolicy.name,
            syncIntervalSeconds = input.syncIntervalSeconds,
            enabled = input.enabled,
        )
    )

    override suspend fun touchSync(id: UUID, etag: String?) = repository.touchSync(id, etag)
}

@ServiceImplementation
class FederationFieldMaskServiceImpl(
    private val repository: FederationFieldMaskRepository,
) : FederationFieldMaskService {
    override suspend fun get(projectId: UUID, peerId: UUID) = repository.get(projectId, peerId)
    override suspend fun upsert(projectId: UUID, peerId: UUID, mask: Long) =
        repository.upsert(projectId, peerId, mask)
}

@ServiceImplementation
class FederationConflictServiceImpl(
    private val repository: FederationConflictRepository,
    private val json: Json,
) : FederationConflictService {

    override suspend fun listForTask(taskId: UUID, offset: Long, limit: Int) =
        repository.listForTask(taskId, offset, limit.coerceIn(1, 200))

    override suspend fun listUnresolved(peerId: UUID, limit: Int) =
        repository.listUnresolved(peerId, limit.coerceIn(1, 500))

    override suspend fun record(
        taskId: UUID,
        peerId: UUID,
        fieldKey: String,
        oldValue: JsonElement,
        newValue: JsonElement,
        triggeredBy: String,
    ): FederationConflict = repository.add(
        FederationConflictInsertParams(
            taskId = taskId,
            peerId = peerId,
            fieldKey = fieldKey,
            oldValue = json.encodeToString(JsonElement.serializer(), oldValue),
            newValue = json.encodeToString(JsonElement.serializer(), newValue),
            triggeredBy = triggeredBy,
        )
    )

    override suspend fun resolve(id: UUID, resolution: FederationConflictResolution) =
        repository.resolve(id, resolution.name)
}

@ServiceImplementation
class FederationPrincipalMappingServiceImpl(
    private val repository: FederationPrincipalMappingRepository,
) : FederationPrincipalMappingService {
    override suspend fun propose(
        peerId: UUID,
        remoteUserId: String,
        proposedProfileId: UUID?,
        remoteEmail: String?,
    ) = repository.propose(peerId, remoteUserId, proposedProfileId, remoteEmail)

    override suspend fun accept(peerId: UUID, remoteUserId: String, profileId: UUID) =
        repository.accept(peerId, remoteUserId, profileId)

    override suspend fun resolve(peerId: UUID, remoteUserId: String) =
        repository.resolve(peerId, remoteUserId)
}

/**
 * R37 — adapter SPI. Per-peer implementations live in their own
 * modules (workops + core-git-providers); Phase 24 ships the
 * contract so admins can wire peers today and the runtime
 * starts honoring them when adapters land.
 */
interface FederationAdapter {
    val kind: FederationPeerKind

    /** Returns the snapshots that have changed since [sinceEtag]. */
    suspend fun pull(peer: FederationPeer, sinceEtag: String?): List<RemoteTaskSnapshot>

    /** Pushes a local task's mask-filtered fields back to the peer. */
    suspend fun push(peer: FederationPeer, snapshot: RemoteTaskSnapshot, fieldsChanged: Set<String>)
}

/**
 * Default no-op adapter for kinds whose concrete implementation
 * hasn't shipped yet. Returning empty lists / raising for push
 * keeps the dispatcher / sync engine running without crashing
 * while admins can still author peers in advance.
 */
class PendingFederationAdapter(
    override val kind: FederationPeerKind,
) : FederationAdapter {
    override suspend fun pull(peer: FederationPeer, sinceEtag: String?): List<RemoteTaskSnapshot> {
        return emptyList()
    }

    override suspend fun push(
        peer: FederationPeer,
        snapshot: RemoteTaskSnapshot,
        fieldsChanged: Set<String>,
    ) {
        throw PendingPhaseImplementationException(
            variant = "FederationAdapter.${kind.name}.push",
            owningPhase = 24,
        )
    }
}

/**
 * Registry of adapters by kind. Production wires the concrete
 * adapters via DI; Phase 24 ships this minimal registry so the
 * sync engine has a uniform lookup.
 */
class FederationAdapterRegistry(
    initial: Map<FederationPeerKind, FederationAdapter> = emptyMap(),
) {
    private val adapters = initial.toMutableMap()

    fun register(adapter: FederationAdapter) {
        adapters[adapter.kind] = adapter
    }

    fun adapterFor(kind: FederationPeerKind): FederationAdapter =
        adapters[kind] ?: PendingFederationAdapter(kind)

    fun adapterFor(peer: FederationPeer): FederationAdapter = adapterFor(peer.kind)
}
