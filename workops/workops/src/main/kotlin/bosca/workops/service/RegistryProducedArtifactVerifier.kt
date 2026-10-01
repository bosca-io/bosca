package bosca.workops.service

import bosca.artifacts.model.ArtifactType
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.git.model.ArtifactDefinition
import bosca.git.model.ArtifactRequirement
import bosca.git.service.ProducedArtifactVerifier
import bosca.git.service.RequiredArtifactVerifier

/**
 * the registry-backed [ProducedArtifactVerifier]: a CI job's declared artifact is
 * "present" when its `namespace/name` repository exists in the registry AND carries the coordinate's
 * version — as a docker TAG for docker artifacts (docker versions are digest-keyed; the human
 * coordinate is the tag), and as a registry VERSION for everything else. Store bundles, values files,
 * and native binaries all ship through the RAW registry (`registry-upload`), so their type strings map
 * there. A type this verifier doesn't know is skipped rather than failed — it can't disprove it.
 *
 * Also the registry-backed [RequiredArtifactVerifier]: the same coordinate resolution
 * answers the consumption question — "is this requirement satisfied?" — with one addition, prefix
 * constraints: a version segment ending in `*` matches any published version/tag with that prefix.
 * Here the unknown-type asymmetry flips: an unknown requirement type is UNSATISFIED, because a gate
 * that waves through what it cannot check isn't a gate.
 */
class RegistryProducedArtifactVerifier(
    private val artifacts: ArtifactRepositoryService,
) : ProducedArtifactVerifier, RequiredArtifactVerifier {

    override suspend fun missing(artifacts: List<ArtifactDefinition>): List<ArtifactDefinition> =
        artifacts.filter { !exists(it) }

    override suspend fun unsatisfied(requirements: List<ArtifactRequirement>): List<ArtifactRequirement> =
        requirements.filter { !satisfied(it) }

    private suspend fun exists(declared: ArtifactDefinition): Boolean {
        val registryType = registryTypeOf(declared.type) ?: return true // unknown type — can't disprove
        val name = registryRepositoryName(declared.coordinate, registryType)
        val version = declared.coordinate.substringAfterLast(':')
        if (name.isBlank() || version.isBlank() || name == declared.coordinate) return false // not name:version
        val repository = artifacts.findRepository(declared.namespace, name, registryType) ?: return false
        return if (registryType == ArtifactType.DOCKER) {
            artifacts.findTag(repository.id, version) != null
        } else {
            artifacts.findVersion(repository.id, version) != null
        }
    }

    private suspend fun satisfied(required: ArtifactRequirement): Boolean {
        val registryType = registryTypeOf(required.type) ?: return false // a gate can't wave through the unknown
        val name = registryRepositoryName(required.coordinate, registryType)
        val version = required.coordinate.substringAfterLast(':')
        if (name.isBlank() || version.isBlank() || name == required.coordinate) return false // not name:version
        val repository = artifacts.findRepository(required.namespace, name, registryType) ?: return false
        val prefix = version.takeIf { it.endsWith("*") }?.dropLast(1)?.removeSuffix(".")
        return if (registryType == ArtifactType.DOCKER) {
            if (prefix != null) artifacts.findTagByPrefix(repository.id, prefix) != null
            else artifacts.findTag(repository.id, version) != null
        } else {
            if (prefix != null) artifacts.findVersionByPrefix(repository.id, prefix) != null
            else artifacts.findVersion(repository.id, version) != null
        }
    }

    /**
     * The registry repository name a coordinate's non-version part resolves to. Maven is the special
     * case: coordinates are written `group:artifact:version` but the maven registry names its
     * repositories `group.artifact` (see MavenRouteSupport), so the remaining `:` becomes a dot —
     * `io.bosca:core:6.0.9` → repository `io.bosca.core`, version `6.0.9`. Already-dotted maven
     * coordinates (`io.bosca.core:6.0.9`) pass through unchanged. Every other type's repository name
     * is the coordinate minus its `:version` suffix.
     */
    private fun registryRepositoryName(coordinate: String, registryType: ArtifactType): String {
        val name = coordinate.substringBeforeLast(':')
        return if (registryType == ArtifactType.MAVEN) name.replace(':', '.') else name
    }

    /** The registry protocol a build-yaml artifact type string publishes through, or null when unknown. */
    private fun registryTypeOf(type: String): ArtifactType? = when (type.lowercase()) {
        "docker" -> ArtifactType.DOCKER
        "helm" -> ArtifactType.HELM
        "maven" -> ArtifactType.MAVEN
        "npm" -> ArtifactType.NPM
        "ml" -> ArtifactType.ML
        "raw", "helm-values",
        "android-aar", "android_aar",
        "ios-framework", "ios_framework",
        "graalvm-native", "graalvm_native",
        "wasm" -> ArtifactType.RAW
        else -> null
    }
}
