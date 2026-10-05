package bosca.kubernetes.model

import kotlinx.serialization.Serializable

/**
 * cert-manager `Certificate` resource. `expiresInDays` / `renewsInDays`
 * are computed controller-side from `status.notAfter` and
 * `status.renewalTime`; `renewsInDays = -1` signals "no renewal
 * scheduled" (the cert is in a terminal state like `Ready=False`).
 */
@Serializable
data class K8sCertificate(
    val id: String,
    val name: String,
    val namespace: String,
    val dns: List<String>,
    val issuer: String,
    val status: String,
    val readySince: String,
    val expiresInDays: Int,
    val renewsInDays: Int,
    val secretName: String,
    val error: String? = null,
)

@Serializable
data class CertificatesResponse(val items: List<K8sCertificate>)

/**
 * cert-manager `Issuer` or `ClusterIssuer`. `type` is derived from
 * the populated key under `spec` (`acme` / `ca` / `vault` /
 * `selfSigned`). `server` is meaningful only for ACME and Vault.
 */
@Serializable
data class K8sIssuer(
    val id: String,
    val kind: String,
    val name: String,
    val type: String,
    val server: String,
    val status: String,
    val age: String,
    val certs: Int,
    val namespace: String? = null,
)

@Serializable
data class IssuersResponse(val items: List<K8sIssuer>)
