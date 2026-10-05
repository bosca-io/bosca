package bosca.workops.deploy

import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * a project's deploy configuration, declared **in git**: a `.bosca/deploy.yaml`
 * at the root of one of the project's repositories, keyed by environment name. Keeping the config in
 * the repository (rather than node settings or a database column) means it is versioned with the code —
 * read at the release's tag ref, the config that ships is the config that was tagged.
 *
 * Environment stanzas are PURE ROUTING — where the version goes and which adapter takes it there.
 * Nothing version-carrying lives here: versions ride registry artifacts (the image, the chart, the
 * per-environment `helm-values` files CI publishes), and the deploy targets apply them.
 *
 * ```yaml
 * environments:
 *   staging:
 *     target: helm-values
 *     config: { clusterId: "…", releaseName: "my-api", namespace: "staging", repo: "…", chart: "my-api" }
 *   production:
 *     target: helm-values
 *     config: { … }
 *   play-production:
 *     target: google_play
 *     config: { packageName: "io.example.app", track: "production" }
 * ```
 */
@Serializable
data class ProjectDeployConfig(
    /** Per-environment deploy declarations, keyed by the workops environment's (program-unique) name. */
    val environments: Map<String, EnvironmentDeployConfig> = emptyMap(),
) {
    /** The declaration for [name] (environment names are case-insensitive), or null when undeclared. */
    fun forEnvironment(name: String): EnvironmentDeployConfig? =
        environments.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * An explicit artifact selector on a deploy target entry: WHICH declared artifact this
 * target ships, by registry coordinates. [coordinate] may interpolate `${{ version }}`. A selector
 * that matches nothing the version's builds declared fails the deploy loudly, naming the selector —
 * never a silent skip.
 */
@Serializable
data class ArtifactSelector(
    /** The artifact type name (e.g. `docker`, `helm-values`); null matches any type. */
    val type: String? = null,
    /** The registry namespace; null matches any. */
    val namespace: String? = null,
    /** The coordinate to match, after `${{ version }}` interpolation. */
    val coordinate: String,
)

/** One deploy target within an environment entry (`targets:` list). */
@Serializable
data class DeployTargetEntry(
    /** The [DeployTargetKind] name (case-insensitive in YAML: `helm`, `google_play`, `app_store`). */
    val target: String = "helm",
    /** Target-specific settings the adapter decodes itself. */
    val config: JsonElement = JsonObject(emptyMap()),
    /** Which declared artifact this target ships; null for targets that move no bytes (App Store). */
    val artifact: ArtifactSelector? = null,
) {
    /** The declared target as a [DeployTargetKind], or null when the name matches no known kind. */
    fun targetKind(): DeployTargetKind? =
        DeployTargetKind.entries.firstOrNull { it.name.equals(target.replace('-', '_'), ignoreCase = true) }

}

/** One environment's deploy declaration: which [DeployTargetKind] adapter, and its settings. */
@Serializable
data class EnvironmentDeployConfig(
    /** The [DeployTargetKind] name (case-insensitive in YAML: `helm`, `google_play`, `app_store`). */
    val target: String = "helm",
    /** Target-specific settings the adapter decodes itself (e.g. the Helm cluster/chart/values). */
    val config: JsonElement = JsonObject(emptyMap()),
    /** The single-target form's artifact selector. */
    val artifact: ArtifactSelector? = null,
    /** Multiple targets for one environment — the single `target:` form is sugar for
     *  a one-entry list; see [effectiveTargets]. */
    val targets: List<DeployTargetEntry> = emptyList(),
    /**
     * The git repository the deploy.yaml was READ from (string UUID) — stamped by the resolver, never
     * declared in the file. Adapters use it to resolve repo-relative references (e.g. the Helm values
     * file defaults to living beside the config) without the file carrying repository UUIDs.
     */
    val sourceRepositoryId: String? = null,
) {
    /** The declared target as a [DeployTargetKind], or null when the name matches no known kind.
     *  Config files spell kinds in kebab-case (`helm-values`); enum names use underscores. */
    fun targetKind(): DeployTargetKind? =
        DeployTargetKind.entries.firstOrNull { it.name.equals(target.replace('-', '_'), ignoreCase = true) }

    /** The declared targets, with the single-`target:` form normalized to a one-entry list. */
    fun effectiveTargets(): List<DeployTargetEntry> =
        targets.ifEmpty { listOf(DeployTargetEntry(target = target, config = config, artifact = artifact)) }
}

/** The committed starter deploy configuration [DeployConfigService.createDeployConfig] produced. */
@Serializable
data class CreatedDeployConfig(
    @kotlinx.serialization.Contextual
    val repositoryId: UUID,
    val branch: String,
    val path: String,
    val commitSha: String,
    /** The generated file content, so callers can display what was committed. */
    val content: String,
)

/**
 * Resolves a project's git-resident deploy configuration ([ProjectDeployConfig]) for a deploy node.
 * The lookup walks the project's repositories and reads `.bosca/deploy.yaml` at the first of the
 * candidate [refs] that has one — deploy nodes pass the release's tag first (config versions with the
 * release) and the default branch as fallback.
 */
interface DeployConfigService : Service {

    /**
     * The deploy declaration for [environmentName] from [projectId]'s repositories, trying each of
     * [refs] in order per repository; null when no repository declares that environment. A file that
     * exists but does not parse fails loudly — a broken deploy config must never deploy with defaults.
     */
    suspend fun forEnvironment(projectId: UUID, environmentName: String, refs: List<String>): EnvironmentDeployConfig?

    /**
     * The parsed `.bosca/deploy.yaml` of [repositoryId] at [ref], or null when the file doesn't exist.
     * A file that exists but does not parse fails loudly. How the Update Helm Values node discovers
     * the repo's helm-target environments and their values files.
     */
    suspend fun read(repositoryId: UUID, ref: String): ProjectDeployConfig?

    /**
     * Validates [content] as [repositoryId]'s deploy.yaml — the push-time check:
     * parse errors, unknown `target:` kinds, configs the target adapter cannot decode, and
     * environment stanzas no linked program declares all surface at push, not at deploy time.
     * Returns human-readable problems; empty means valid.
     */
    suspend fun validate(repositoryId: UUID, content: String): List<String>

    /**
     * Generates a starter `.bosca/deploy.yaml` for [projectId] — one stanza per (non-ephemeral)
     * environment of the project's program, with the deploy target chosen from each environment's
     * [bosca.workops.model.environment.EnvironmentTargetType] and placeholder settings to fill in —
     * and COMMITS it to [repositoryId]'s `main` branch. Fails when the repository is not linked to
     * the project, when the program has no environments yet, or when the file already exists (edit
     * it in the repository instead — this never overwrites).
     */
    suspend fun createDeployConfig(
        projectId: UUID,
        repositoryId: UUID,
        authorName: String,
        authorEmail: String,
    ): CreatedDeployConfig

    companion object {
        /** Repository-relative path of the deploy configuration file. */
        const val DEPLOY_CONFIG_PATH = ".bosca/deploy.yaml"
    }
}
