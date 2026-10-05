package bosca.kubernetes.service

import bosca.kubernetes.repository.ClusterCredentialRepository
import bosca.kubernetes.repository.ClusterCredential
import bosca.security.encryption.EncryptionService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class ClusterCredentialServiceImpl(
    private val repository: ClusterCredentialRepository,
    private val encryption: EncryptionService,
) : ClusterCredentialService {

    override suspend fun store(clusterId: UUID, kubeconfig: String) {
        val encrypted = encryption.encrypt(kubeconfig.encodeToByteArray(), clusterId)
        repository.upsert(ClusterCredential(clusterId, encrypted.nonce, encrypted.data))
    }

    override suspend fun load(clusterId: UUID): String? {
        val row = repository.get(clusterId) ?: return null
        val plaintext = encryption.decrypt(
            EncryptionService.Encrypted(row.nonce, row.data),
            clusterId,
        )
        return plaintext.decodeToString()
    }

    override suspend fun delete(clusterId: UUID) {
        repository.delete(clusterId)
    }
}
