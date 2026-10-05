package bosca.kubernetes.service

import bosca.kubernetes.model.Cluster
import bosca.kubernetes.model.RegisterClusterInput
import bosca.kubernetes.model.UpdateClusterInput
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Owns cluster registration and metadata together with the encrypted
 * kubeconfig managed by [ClusterCredentialService].
 *
 * Connectivity belongs to the standalone Kubernetes controller; this service
 * stores the information needed to find a cluster and records observed state.
 */
interface ClusterService : Service {

    /** Returns the active cluster identified by [id], or null when it is unknown. */
    suspend fun getById(id: UUID): Cluster?

    /** Returns the active cluster named [name], or null when it is unknown. */
    suspend fun getByName(name: String): Cluster?

    /** Returns all active registered clusters. */
    suspend fun list(): List<Cluster>

    /** Registers [input] and encrypts its kubeconfig before persistence. */
    suspend fun register(input: RegisterClusterInput): Cluster

    /** Updates mutable cluster metadata using [expectedVersion] for optimistic locking. */
    suspend fun update(id: UUID, input: UpdateClusterInput, expectedVersion: Long): Cluster

    /** Validates and replaces the encrypted kubeconfig for [id]. */
    suspend fun rotateKubeconfig(id: UUID, kubeconfig: String): Cluster

    /** Records the latest state observed by the Kubernetes controller. */
    suspend fun updateObservedState(
        id: UUID,
        serverVersion: String,
        health: String,
        nodes: Int,
        pods: Int,
    ): Cluster?

    /** Soft-deletes [id] and removes its stored credential. */
    suspend fun remove(id: UUID): Boolean

    /** Returns active clusters paired with decrypted kubeconfigs for controller use. */
    suspend fun listWithCredentials(): List<Pair<Cluster, String?>>
}
