package bosca.bml.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Discovers nested source/package links without recursing through directory cycles or build output. */
internal abstract class BmlLibraryDependencyLocations : ValueSource<BmlLibraryDependencyPaths, BmlLibraryDependencyLocations.Parameters> {
    interface Parameters : ValueSourceParameters {
        val sourceDirectories: ListProperty<File>
        val buildDirectory: DirectoryProperty
    }

    /** Follows source and installed-package links until no further lookup directories are found. */
    override fun obtain(): BmlLibraryDependencyPaths {
        val buildRoot = parameters.buildDirectory.get().asFile.canonicalFile.toPath()
        val directories = sortedSetOf<String>()
        val links = sortedMapOf<String, String>()
        val pending = ArrayDeque<File>()
        val visited = mutableSetOf<File>()
        val ignored = setOf(".git", ".gradle", ".kotlin")

        fun dependencyLookup(source: File) {
            generateSequence(source) { it.parentFile }.forEach { directory ->
                val modules = directory.resolve("node_modules")
                if (directories.add(modules.path)) pending.add(modules)
            }
        }
        val configurations = BmlLibraryConfigurations(::dependencyLookup)
        fun lookupFrom(source: File) {
            dependencyLookup(source)
            configurations.ancestors(source)
        }
        parameters.sourceDirectories.get().forEach { source ->
            val root = source.absoluteFile.normalize()
            lookupFrom(root)
            pending.add(root)
        }
        while (pending.isNotEmpty()) {
            val path = pending.removeFirst()
            val real = path.canonicalFile
            if (real.toPath().startsWith(buildRoot)) continue
            if (path != real) {
                links[path.path] = real.path
                lookupFrom(if (real.isFile) real.parentFile else real)
            }
            if (!real.isDirectory) continue
            if (path.name == "node_modules") directories.add(path.path)
            if (!visited.add(real)) continue
            configurations.local(real, includeMissing = false)
            Files.newDirectoryStream(real.toPath()).use { entries ->
                entries.sortedBy { it.fileName.toString() }.forEach { entry ->
                    if (entry.fileName.toString() !in ignored &&
                        (Files.isSymbolicLink(entry) || Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
                    ) pending.add(entry.toFile())
                }
            }
        }
        return BmlLibraryDependencyPaths(directories.map(::File), links, configurations.files)
    }
}
