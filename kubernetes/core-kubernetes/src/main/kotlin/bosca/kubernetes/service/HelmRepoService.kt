package bosca.kubernetes.service

import bosca.kubernetes.model.HelmRepoCredentials
import bosca.kubernetes.repository.HelmRepo
import bosca.service.Service

/**
 * Owns the configured Helm repository registry and its paired encrypted
 * credentials. Controllers use this service instead of reaching into either
 * persistence table directly.
 */
interface HelmRepoService : Service {

    /** Returns all configured Helm repositories. */
    suspend fun list(): List<HelmRepo>

    /** Returns the repository named [name], or null when it is not configured. */
    suspend fun getByName(name: String): HelmRepo?

    /** Persists a validated repository index and replaces its stored credentials. */
    suspend fun save(
        name: String,
        url: String,
        type: String,
        indexYaml: String,
        repoCredentials: HelmRepoCredentials?,
    ): HelmRepo

    /** Replaces the cached index for [name], returning the updated row when the repository exists. */
    suspend fun updateIndex(name: String, indexYaml: String): HelmRepo?

    /** Returns decrypted credentials for [name], or null for a public or unknown repository. */
    suspend fun credentials(name: String): HelmRepoCredentials?

    /** Removes the repository named [name] and its cascading stored credential. */
    suspend fun remove(name: String)
}
