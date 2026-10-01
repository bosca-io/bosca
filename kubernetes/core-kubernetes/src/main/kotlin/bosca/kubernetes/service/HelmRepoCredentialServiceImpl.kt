package bosca.kubernetes.service

import bosca.kubernetes.model.HelmRepoCredentials
import bosca.kubernetes.repository.HelmRepoCredentialRepository
import bosca.kubernetes.repository.HelmRepoCredential
import bosca.security.encryption.EncryptionService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.Json

@ServiceImplementation
class HelmRepoCredentialServiceImpl(
    private val repository: HelmRepoCredentialRepository,
    private val encryption: EncryptionService,
    private val json: Json,
) : HelmRepoCredentialService {

    override suspend fun store(repoName: String, credentials: HelmRepoCredentials) {
        val id = repository.get(repoName)?.id ?: UUID.random()
        val plaintext = json.encodeToString(HelmRepoCredentials.serializer(), credentials).encodeToByteArray()
        val encrypted = encryption.encrypt(plaintext, id)
        repository.upsert(HelmRepoCredential(repoName, id, encrypted.nonce, encrypted.data))
    }

    override suspend fun load(repoName: String): HelmRepoCredentials? {
        val row = repository.get(repoName) ?: return null
        val plaintext = encryption.decrypt(EncryptionService.Encrypted(row.nonce, row.data), row.id)
        return json.decodeFromString(HelmRepoCredentials.serializer(), plaintext.decodeToString())
    }

    override suspend fun delete(repoName: String) {
        repository.delete(repoName)
    }
}
