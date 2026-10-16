package bosca.bml.gradle

import java.io.File
import java.io.Serializable

/** Dependency trees, link identities, and configuration candidates used by browser module resolution. */
internal data class BmlLibraryDependencyPaths(
    val directories: List<File>,
    val links: Map<String, String>,
    val configurations: List<File>,
) : Serializable
