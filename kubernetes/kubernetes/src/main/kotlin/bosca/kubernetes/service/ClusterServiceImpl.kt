package bosca.kubernetes.service

import bosca.kubernetes.model.Cluster
import bosca.kubernetes.model.RegisterClusterInput
import bosca.kubernetes.model.UpdateClusterInput
import bosca.kubernetes.repository.ClusterRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class ClusterServiceImpl(
    private val repository: ClusterRepository,
    private val credentials: ClusterCredentialService,
) : ClusterService {

    override suspend fun getById(id: UUID): Cluster? = repository.getById(id)

    override suspend fun getByName(name: String): Cluster? = repository.getByName(name)

    override suspend fun list(): List<Cluster> = repository.list()

    override suspend fun register(input: RegisterClusterInput): Cluster {
        require(input.name.isNotBlank()) { "Cluster name is required" }
        requireLooksLikeKubeconfig(input.kubeconfig)
        repository.getByName(input.name)?.let {
            error("A cluster named ${input.name} is already registered")
        }

        val id = UUID.random()
        val cluster = repository.add(
            id = id,
            name = input.name,
            provider = input.provider,
            region = input.region,
            environment = input.environment.name,
        )
        credentials.store(id, input.kubeconfig)
        return cluster
    }

    override suspend fun update(id: UUID, input: UpdateClusterInput, expectedVersion: Long): Cluster =
        repository.updateMetadata(
            id = id,
            name = input.name,
            environment = input.environment?.name,
            expectedVersion = expectedVersion,
        ) ?: error("Cluster $id update failed — version mismatch or not found")

    override suspend fun rotateKubeconfig(id: UUID, kubeconfig: String): Cluster {
        requireLooksLikeKubeconfig(kubeconfig)
        val cluster = repository.getById(id) ?: error("Cluster $id not found")
        credentials.store(id, kubeconfig)
        return cluster
    }

    override suspend fun updateObservedState(
        id: UUID,
        serverVersion: String,
        health: String,
        nodes: Int,
        pods: Int,
    ): Cluster? = repository.updateObservedState(id, serverVersion, health, nodes, pods)

    override suspend fun remove(id: UUID): Boolean {
        val deleted = repository.softDelete(id) ?: return false
        credentials.delete(deleted.id)
        return true
    }

    override suspend fun listWithCredentials(): List<Pair<Cluster, String?>> =
        list().map { it to credentials.load(it.id) }

    private fun requireLooksLikeKubeconfig(kubeconfig: String) {
        require(kubeconfig.isNotBlank()) { "kubeconfig is required" }
        require("apiVersion:" in kubeconfig && "clusters:" in kubeconfig) {
            "kubeconfig does not look like a kubeconfig document (missing apiVersion / clusters)"
        }
    }
}
