package bosca.workops.service

import bosca.git.service.CommitFileInput
import bosca.git.service.RepositoryWriteService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.deploy.ArtifactSelector
import bosca.workops.deploy.CreatedDeployConfig
import bosca.workops.deploy.DeployConfigService
import bosca.workops.deploy.DeployTargetEntry
import bosca.workops.deploy.EnvironmentDeployConfig
import bosca.workops.deploy.ProjectDeployConfig
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.EnvironmentTargetType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

/**
 * Reads a project's git-resident deploy configuration: for each of the project's repositories,
 * `.bosca/deploy.yaml` is read at the first candidate ref that has it (the deploy nodes pass the
 * release's tag first, then the default branch), parsed, and the requested environment's declaration
 * returned. A file that exists but is malformed fails loudly — a broken config never deploys defaults.
 */
@ServiceImplementation
class DeployConfigServiceImpl(
    private val projectRepositories: ProjectRepositoryService,
    private val repositoryWrite: RepositoryWriteService,
    private val projectService: ProjectService,
    private val environmentService: EnvironmentService,
) : DeployConfigService {

    override suspend fun validate(repositoryId: UUID, content: String): List<String> {
        val config = try {
            parse(content, repositoryId, "push")
        } catch (e: IllegalStateException) {
            return listOf(e.message ?: "deploy config is malformed")
        }
        val problems = mutableListOf<String>()
        if (config.environments.isEmpty()) {
            problems.add("deploy config declares no environments")
        }

        // Every stanza must name an environment a linked program declares — by KEY (the program
        // link) or display name (accepted while relay-era files migrate). An unlinked repository
        // skips this check: nothing to validate against is not an error.
        val linkedEnvironments = projectRepositories.listByRepository(repositoryId)
            .mapNotNull { link -> projectService.getById(link.projectId)?.programId }
            .distinct()
            .flatMap { environmentService.listByProgram(it) }
        if (linkedEnvironments.isNotEmpty()) {
            val known = linkedEnvironments.flatMap { listOf(it.key.lowercase(), it.name.lowercase()) }.toSet()
            for (name in config.environments.keys) {
                if (name.lowercase() !in known) {
                    problems.add(
                        "environment '$name' is not declared by any linked program — known keys: " +
                            linkedEnvironments.map { it.key }.distinct().sorted().joinToString()
                    )
                }
            }
        }

        for ((name, declaration) in config.environments) {
            for (entry in declaration.effectiveTargets()) {
                val kind = entry.targetKind()
                if (kind == null) {
                    problems.add(
                        "environment '$name' has unknown target '${entry.target}' — known: " +
                            bosca.workops.deploy.DeployTargetKind.entries
                                .joinToString { it.name.lowercase().replace('_', '-') }
                    )
                    continue
                }
                val artifact = entry.artifact
                if (artifact != null && artifact.coordinate.isBlank()) {
                    problems.add("environment '$name' (${entry.target}): artifact selector has a blank coordinate")
                }
                if (kind == bosca.workops.deploy.DeployTargetKind.GOOGLE_PLAY) {
                    val artifact = entry.artifact
                    if (artifact == null) {
                        problems.add(
                            "environment '$name' (${entry.target}): google_play requires an artifact selector " +
                                "with namespace and coordinate"
                        )
                    } else {
                        if (artifact.namespace.isNullOrBlank()) {
                            problems.add("environment '$name' (${entry.target}): google_play artifact requires namespace")
                        }
                        if (artifact.coordinate.substringBeforeLast(':') == artifact.coordinate ||
                            artifact.coordinate.substringAfterLast(':').isBlank()
                        ) {
                            problems.add(
                                "environment '$name' (${entry.target}): google_play artifact coordinate must be name:version"
                            )
                        }
                    }
                }
                val adapter = try {
                    bosca.di.provide<bosca.workops.deploy.DeployTarget>(name = kind.name)
                } catch (e: bosca.di.MissingProviderException) {
                    continue // No adapter in this deployment — its config cannot be checked here.
                }
                for (problem in adapter.validateConfig(entry.config)) {
                    problems.add("environment '$name' (${entry.target}): $problem")
                }
            }
        }
        return problems
    }

    override suspend fun forEnvironment(
        projectId: UUID,
        environmentName: String,
        refs: List<String>,
    ): EnvironmentDeployConfig? {
        for (repository in projectRepositories.list(projectId)) {
            for (ref in refs) {
                val content = repositoryWrite.readFile(
                    repository.repositoryId, ref, DeployConfigService.DEPLOY_CONFIG_PATH,
                ) ?: continue
                val environment = parse(content, repository.repositoryId, ref).forEnvironment(environmentName)
                    // The FIRST config file found is authoritative for this project — an environment it
                    // doesn't declare is undeclared, not a reason to fall through to older refs/repos.
                    ?: return null
                // Stamp where the file came from, so adapters can resolve repo-relative references
                // (e.g. a values file beside the config) without UUIDs in the file itself.
                return environment.copy(sourceRepositoryId = repository.repositoryId.toString())
            }
        }
        return null
    }

    override suspend fun createDeployConfig(
        projectId: UUID,
        repositoryId: UUID,
        authorName: String,
        authorEmail: String,
    ): CreatedDeployConfig {
        val project = projectService.getById(projectId) ?: error("Project $projectId not found")
        require(projectRepositories.list(projectId).any { it.repositoryId == repositoryId }) {
            "Repository $repositoryId is not linked to project '${project.name}'"
        }
        val path = DeployConfigService.DEPLOY_CONFIG_PATH
        if (repositoryWrite.readFile(repositoryId, BRANCH, path) != null) {
            error("This repository already has $path — edit it in the repository (setup never overwrites)")
        }
        val environments = environmentService.listByProgram(project.programId).filterNot { it.ephemeral }
        if (environments.isEmpty()) {
            error("The project's program has no environments yet — create them (Releases → Environments) first")
        }
        val content = render(project.key.lowercase(), environments)
        val result = repositoryWrite.commitFile(
            CommitFileInput(
                repositoryId = repositoryId,
                branch = BRANCH,
                path = path,
                content = content,
                message = "Add $path deploy configuration",
                authorName = authorName,
                authorEmail = authorEmail,
            ),
        )
        return CreatedDeployConfig(
            repositoryId = repositoryId,
            branch = result.branch,
            path = result.path,
            commitSha = result.commitSha,
            content = content,
        )
    }

    /**
     * Renders the starter file: one stanza per environment, its adapter chosen from the environment's
     * declared target type. Hand-rendered (not snakeyaml-dumped) so the scaffold carries TODO comments.
     * Stanzas are pure routing: versions ride registry artifacts (image, chart, per-environment
     * helm-values files published by CI), so no values keys or file paths appear here.
     */
    private fun render(projectKey: String, environments: List<Environment>): String = buildString {
        appendLine("# Bosca deploy configuration — read at each release's tag ref, so what ships is what was tagged.")
        appendLine("# Fill in the TODO fields before the first release run.")
        appendLine("environments:")
        for (env in environments) {
            appendLine("  ${env.name.lowercase()}:")
            when (env.targetType) {
                EnvironmentTargetType.GENERIC -> {
                    appendLine("    target: helm-values")
                    appendLine("    config:")
                    appendLine("      clusterId: \"\"          # TODO: the target cluster's id")
                    appendLine("      releaseName: \"$projectKey\"")
                    appendLine("      namespace: \"${env.name.lowercase()}\"")
                    appendLine("      repo: \"\"               # TODO: helm repo name")
                    appendLine("      chart: \"$projectKey\"")
                    appendLine("      chartVersion: \"\"       # TODO: the chart's own version (blank = the release version)")
                }
                EnvironmentTargetType.PLAY_TRACK -> {
                    appendLine("    target: google_play")
                    appendLine("    artifact:")
                    appendLine("      type: android-aar")
                    appendLine("      namespace: \"\"          # TODO: registry namespace containing the AAB")
                    appendLine("      coordinate: \"$projectKey:\${{ version }}\"")
                    appendLine("    config:")
                    appendLine("      packageName: \"\"        # TODO: the app's application id")
                    appendLine("      track: \"${env.targetRef?.ifBlank { null } ?: "internal"}\"")
                    appendLine("      serviceAccountSecret: \"play-sa\"   # pipeline secret NAME holding the service-account JSON")
                }
                EnvironmentTargetType.TESTFLIGHT, EnvironmentTargetType.APP_STORE -> {
                    appendLine("    target: app_store")
                    appendLine("    config:")
                    appendLine("      bundleId: \"\"           # TODO: the app's bundle id")
                    if (env.targetType == EnvironmentTargetType.TESTFLIGHT) {
                        appendLine("      testflightGroups: [\"${env.targetRef.orEmpty()}\"]")
                    }
                    appendLine("      ascKeySecret: \"asc-key\"           # pipeline secret NAME holding the App Store Connect key")
                }
            }
        }
    }

    override suspend fun read(repositoryId: UUID, ref: String): ProjectDeployConfig? {
        val content = repositoryWrite.readFile(repositoryId, ref, DeployConfigService.DEPLOY_CONFIG_PATH)
            ?: return null
        return parse(content, repositoryId, ref)
    }

    /** Parses a deploy.yaml into the typed [ProjectDeployConfig]; malformed content fails the deploy. */
    private fun parse(content: String, repositoryId: UUID, ref: String): ProjectDeployConfig {
        val root = try {
            @Suppress("UNCHECKED_CAST")
            (Yaml().load(content) as? Map<String, Any?>)
                ?: error("deploy config in $repositoryId@$ref must be a YAML mapping")
        } catch (e: YAMLException) {
            error("deploy config in $repositoryId@$ref is malformed YAML: ${e.message}")
        }
        val environments = (root["environments"] as? Map<*, *>)
            ?: error("deploy config in $repositoryId@$ref has no 'environments' mapping")
        return ProjectDeployConfig(
            environments = environments.entries.associate { (name, declaration) ->
                val decl = declaration as? Map<*, *>
                    ?: error("deploy config environment '$name' in $repositoryId@$ref must be a mapping")
                name.toString() to EnvironmentDeployConfig(
                    target = decl["target"]?.toString() ?: "helm",
                    config = toJsonElement(decl["config"] ?: emptyMap<String, Any?>()),
                    artifact = parseArtifactSelector(decl["artifact"], name, repositoryId, ref),
                    targets = parseTargets(decl["targets"], name, repositoryId, ref),
                )
            },
        )
    }

    /** The `targets:` list — several deploy targets for one environment. */
    private fun parseTargets(value: Any?, environment: Any?, repositoryId: UUID, ref: String): List<DeployTargetEntry> {
        if (value == null) return emptyList()
        val list = value as? List<*>
            ?: error("deploy config environment '$environment' in $repositoryId@$ref: 'targets' must be a list")
        return list.map { entry ->
            val map = entry as? Map<*, *>
                ?: error("deploy config environment '$environment' in $repositoryId@$ref: each targets entry must be a mapping")
            DeployTargetEntry(
                target = map["target"]?.toString() ?: "helm",
                config = toJsonElement(map["config"] ?: emptyMap<String, Any?>()),
                artifact = parseArtifactSelector(map["artifact"], environment, repositoryId, ref),
            )
        }
    }

    private fun parseArtifactSelector(value: Any?, environment: Any?, repositoryId: UUID, ref: String): ArtifactSelector? {
        if (value == null) return null
        val map = value as? Map<*, *>
            ?: error("deploy config environment '$environment' in $repositoryId@$ref: 'artifact' must be a mapping")
        val coordinate = map["coordinate"]?.toString()
            ?: error("deploy config environment '$environment' in $repositoryId@$ref: artifact selector requires 'coordinate'")
        return ArtifactSelector(
            type = map["type"]?.toString(),
            namespace = map["namespace"]?.toString(),
            coordinate = coordinate,
        )
    }

    private companion object {
        /** The branch starter configs commit to — matches the platform's git-write convention. */
        const val BRANCH = "main"
    }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toDouble())
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) })
        is List<*> -> JsonArray(value.map { toJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }
}
