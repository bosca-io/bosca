package bosca.core.security

import bosca.core.security.model.Identity

/** Persists the last server-verified identity independently of authentication tokens. */
interface IdentityStorage {
    suspend fun getIdentity(): Identity?

    /** Persists [identity], or removes the stored identity when it is null. */
    suspend fun setIdentity(identity: Identity?)
}
