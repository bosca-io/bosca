package bosca.artifacts.model

/**
 * Identifies the registry protocol an artifact repository serves, determining
 * which HTTP API contract clients use to push and pull artifacts.
 */
enum class ArtifactType(val value: String) {
    DOCKER("docker"),
    HELM("helm"),
    MAVEN("maven"),
    NPM("npm"),
    RAW("raw"),
    ML("ml");

    companion object {
        fun fromValue(value: String): ArtifactType =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown artifact type: '$value'. Valid types: ${entries.joinToString { it.value }}")
    }
}
