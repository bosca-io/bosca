package bosca.kubernetes.service

import bosca.kubernetes.model.HelmRepoCredentials
import bosca.kubernetes.repository.HelmRepoRepository
import bosca.kubernetes.repository.HelmRepo
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class HelmRepoServiceImpl(
    private val repository: HelmRepoRepository,
    private val credentials: HelmRepoCredentialService,
) : HelmRepoService {

    override suspend fun list(): List<HelmRepo> = repository.list()

    override suspend fun getByName(name: String): HelmRepo? = repository.getByName(name)

    override suspend fun save(
        name: String,
        url: String,
        type: String,
        indexYaml: String,
        repoCredentials: HelmRepoCredentials?,
    ): HelmRepo {
        val row = repository.upsert(name, url, type, indexYaml)
        if (repoCredentials == null) credentials.delete(name)
        else credentials.store(name, repoCredentials)
        return row
    }

    override suspend fun updateIndex(name: String, indexYaml: String): HelmRepo? =
        repository.updateIndex(name, indexYaml)

    override suspend fun credentials(name: String): HelmRepoCredentials? = credentials.load(name)

    override suspend fun remove(name: String) {
        repository.delete(name)
    }
}
