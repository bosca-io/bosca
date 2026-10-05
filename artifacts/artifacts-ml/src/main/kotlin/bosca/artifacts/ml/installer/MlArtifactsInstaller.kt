package bosca.artifacts.ml.installer

import bosca.artifacts.service.ArtifactRepositoryService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.security.model.Principal
import bosca.security.model.SimplePasswordAttributes
import bosca.security.service.ApiTokenInput
import bosca.security.service.ApiTokenService
import bosca.security.service.SecurityService
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import kotlin.io.encoding.Base64

/**
 * Provisions the ML model artifacts on first boot: the non-public [NAMESPACE] namespace, a dedicated
 * service principal, and a scoped push + pull token for it. The raw tokens are logged once (like the
 * bootstrap admin password in InitialInstaller) so an operator can place them in the `recommendation-ml`
 * secret the trainer/loader read (`artifacts_push_token` / `artifacts_pull_token`).
 *
 * The package version gate runs this once; the per-resource existence checks make a manual re-run a no-op.
 * A scoped token is gated purely by its scope (the artifact permission evaluator ignores group permissions
 * for scoped principals), so no namespace group grant is needed for the trainer/loader to push/pull.
 */
class MlArtifactsInstaller(
    private val securityService: SecurityService,
    private val apiTokenService: ApiTokenService,
    private val artifactService: ArtifactRepositoryService,
) : PackageInstaller {
    override val version: String = "1.0.0"

    private val secureRandom by lazy { SecureRandom() }

    private fun generatePassword(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).encode(bytes)
    }

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        if (artifactService.getNamespaceByName(NAMESPACE) == null) {
            artifactService.createNamespace(NAMESPACE, public = false)
            log.info("Created non-public artifact namespace '{}' for ML models", NAMESPACE)
        }

        // Idempotent: the service principal owns the scoped tokens; if it already exists the tokens were
        // already minted + logged on a prior run, so don't mint duplicates.
        if (securityService.getPrincipalByIdentifier(PRINCIPAL) != null) return

        val principal = securityService.addPrincipal(
            Principal(verified = true, anonymous = false),
            SimplePasswordAttributes(PRINCIPAL, generatePassword()),
        )
        val push = apiTokenService.createToken(
            principal.id,
            ApiTokenInput(
                name = "ml-model-push",
                description = "Recommendation trainer: push trained models to the '$NAMESPACE' namespace",
                scopes = listOf("artifacts:ml:$NAMESPACE/*:*:push"),
            ),
            principal.id,
        )
        val pull = apiTokenService.createToken(
            principal.id,
            ApiTokenInput(
                name = "ml-model-pull",
                description = "Model loader: pull trained models from the '$NAMESPACE' namespace",
                scopes = listOf("artifacts:ml:$NAMESPACE/*:*:pull"),
            ),
            principal.id,
        )
        log.warn(
            "Provisioned ML artifact access. Store these tokens in the recommendation-ml secret (shown once):\n" +
                "  artifacts_push_token (trainer): {}\n" +
                "  artifacts_pull_token (loader):  {}",
            push.rawToken, pull.rawToken,
        )
    }

    companion object {
        /** The artifact namespace ML models live under (custom `ml` type). */
        const val NAMESPACE = "model"
        /** The service principal that owns the ML push/pull tokens. */
        const val PRINCIPAL = "ml-service"
        private val log = LoggerFactory.getLogger(MlArtifactsInstaller::class.java)
    }
}
