package bosca.kubernetes.service

import bosca.kubernetes.model.HelmRepoCredentials
import bosca.service.Service

/**
 * Encrypts and decrypts private Helm repository credentials.
 *
 * The repository row stores only a credential id, nonce, and ciphertext. The
 * username and password exist as plaintext only while servicing a controller
 * request or invoking Helm.
 */
interface HelmRepoCredentialService : Service {

    /** Encrypts and stores [credentials], preserving the encryption id during rotation. */
    suspend fun store(repoName: String, credentials: HelmRepoCredentials)

    /** Returns decrypted credentials for [repoName], or null for a public repository. */
    suspend fun load(repoName: String): HelmRepoCredentials?

    /** Removes any stored credential for [repoName]. Idempotent. */
    suspend fun delete(repoName: String)
}
