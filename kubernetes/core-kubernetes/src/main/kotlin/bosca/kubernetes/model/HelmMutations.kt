package bosca.kubernetes.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * GraphQL `input HelmInstallInput`. Resolver-facing shape — maps onto
 * [HelmInstallRequest] for the controller call, with the `cluster`
 * field traveling via the URL path instead of the body.
 */
@Serializable
data class HelmInstallInput(
    @Contextual val cluster: UUID,
    val name: String,
    val namespace: String,
    val createNamespace: Boolean = false,
    val repo: String,
    val chart: String,
    val version: String,
    val values: String? = null,
    val dryRun: Boolean = false,
)

/**
 * GraphQL `input HelmUpgradeInput`. `repo` and `chart` come from the
 * studio's "Upgrade" flow which pre-fills both from the current
 * release row — helm itself needs both to fetch the new chart tarball,
 * and we surface them on the input so the GraphQL field signature
 * matches the resolver signature exactly.
 */
@Serializable
data class HelmUpgradeInput(
    @Contextual val cluster: UUID,
    val name: String,
    val namespace: String,
    val repo: String,
    val chart: String,
    val version: String,
    val values: String? = null,
    val dryRun: Boolean = false,
    val resetValues: Boolean = false,
)

/**
 * Wire request body for `POST /clusters/{id}/helm/install`. The
 * cluster id is carried on the URL — the body holds everything else
 * helm needs to materialise a release.
 */
@Serializable
data class HelmInstallRequest(
    val name: String,
    val namespace: String,
    val createNamespace: Boolean = false,
    val repo: String,
    val chart: String,
    val version: String,
    val values: String? = null,
    val dryRun: Boolean = false,
)

/** Wire request body for `POST /clusters/{id}/helm/upgrade`. */
@Serializable
data class HelmUpgradeRequest(
    val name: String,
    val namespace: String,
    val version: String,
    val values: String? = null,
    val dryRun: Boolean = false,
    val resetValues: Boolean = false,
)

/** Wire request body for `POST /clusters/{id}/helm/rollback`. */
@Serializable
data class HelmRollbackRequest(
    val name: String,
    val namespace: String,
    val toRevision: Int,
)
