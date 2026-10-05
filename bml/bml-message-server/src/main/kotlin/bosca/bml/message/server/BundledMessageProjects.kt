package bosca.bml.message.server

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Email project versions shipped with the service as a cold-start fallback. The directory follows
 * the runtime cache layout: `<root>/<project>/<version>/<project>.jar`.
 *
 * Bundled versions never supersede an active registry version. They only make a project available
 * when a fresh process cannot list or download it from the artifacts registry; the ordinary poll
 * promotes the project to the latest published version once the registry is available again.
 */
class BundledMessageProjects(root: File?) {
    data class Version(val project: String, val version: String, val jar: File)

    private val versionsByProject: Map<String, List<Version>> = root
        ?.takeIf(File::isDirectory)
        ?.listFiles()
        .orEmpty()
        .filter(File::isDirectory)
        .associate { projectDir ->
            projectDir.name to projectDir.listFiles()
                .orEmpty()
                .filter(File::isDirectory)
                .mapNotNull { versionDir ->
                    val jars = versionDir.listFiles()
                        .orEmpty()
                        .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
                    when (jars.size) {
                        0 -> null
                        1 -> Version(projectDir.name, versionDir.name, jars.single())
                        else -> {
                            log.warn(
                                "bml-message: ignoring bundled {}@{} because it contains {} jars",
                                projectDir.name,
                                versionDir.name,
                                jars.size,
                            )
                            null
                        }
                    }
                }
                .sortedByDescending(Version::version)
        }
        .filterValues { it.isNotEmpty() }

    val projects: Set<String> get() = versionsByProject.keys

    fun versions(project: String): List<Version> = versionsByProject[project].orEmpty()

    fun latest(project: String): Version? = versions(project).firstOrNull()

    fun jarFor(project: String, version: String): File? =
        versions(project).firstOrNull { it.version == version }?.jar

    companion object {
        val Empty = BundledMessageProjects(null)

        private val log = LoggerFactory.getLogger(BundledMessageProjects::class.java)
    }
}
