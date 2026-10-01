package bosca.core.security.model

import bosca.core.security.type.CredentialType
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * An authenticated user account, mirroring the backend `Principal` type.
 *
 * [credentials] is only populated by `getCurrentPrincipal` (it is absent from
 * the `LoginResponse.principal` selection), so it defaults to empty; callers
 * that need the login identifier should read it after a profile fetch.
 *
 * Port of `Principal` from types.ts (extended with [credentials], which a
 * verification screen needs to surface the pending account's identifier).
 */
@Serializable
data class Principal(
    val id: Uuid,
    val verified: Boolean,
    val primaryProfileId: Uuid?,
    val credentials: List<Credential> = emptyList(),
)

/** A login credential associated with a [Principal] (e.g. an email + password). */
@Serializable
data class Credential(
    val identifier: String,
    val type: CredentialType,
)
