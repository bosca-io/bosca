package bosca.cli

/**
 * The build version baked into the binary at compile time.
 *
 * The value is read from the `/bosca/cli/version.txt` classpath resource that
 * `generateVersionResource` writes during the build (see build.gradle.kts). It
 * is the same string the release pipeline publishes to the artifact registry,
 * so the update check can compare the two directly.
 */
object Version {

    /** Placeholder used for local/unreleased builds where no version was injected. */
    const val DEV = "dev"

    /** Default project version for builds with no RELEASE_VERSION / -Pbosca.version. */
    private const val UNRELEASED_DEFAULT = "0.0.1"

    /** The current build version, e.g. "5.8.4", or [DEV] when not baked in. */
    val current: String by lazy {
        runCatching {
            Version::class.java.getResourceAsStream("/bosca/cli/version.txt")
                ?.bufferedReader()
                ?.use { it.readText().trim() }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: DEV
    }

    /**
     * True for local/unreleased builds where checking for a newer release is
     * meaningless (the placeholder version, or the default project version that
     * a plain `./gradlew build` produces). Used to suppress the update notice.
     */
    val isDev: Boolean
        get() = current == DEV || current == UNRELEASED_DEFAULT
}
