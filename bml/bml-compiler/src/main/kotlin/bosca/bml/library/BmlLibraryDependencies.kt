package bosca.bml.library

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Discovers compiled BML contracts without loading classes or reading producer source attachments. */
object BmlLibraryDependencies {
    /**
     * Reads manifests from resolved binary archives and exploded resource directories in [classpath].
     * Ordinary dependencies without a manifest are ignored. Canonical duplicate paths and identical
     * manifests deduplicate; conflicting versions, contracts, or generated packages fail with both
     * artifact locations. Missing, unreadable, and malformed inputs fail rather than disappearing.
     */
    fun read(classpath: Iterable<File>): List<BmlLibraryDependency> {
        val seen = mutableSetOf<File>()
        val libraries = linkedMapOf<String, BmlLibraryDependency>()
        val packages = mutableMapOf<String, BmlLibraryDependency>()
        for (entry in classpath) {
            val artifact = entry.canonicalFile
            if (!seen.add(artifact)) continue
            require(artifact.exists()) { "Missing BML dependency classpath entry: $artifact" }
            val source = readManifest(artifact) ?: continue
            val manifest = try {
                BmlLibraryArtifacts.decode(source)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid BML manifest in $artifact: ${failure.message}", failure)
            }
            val dependency = BmlLibraryDependency(artifact, manifest)
            val previous = libraries[manifest.library]
            if (previous != null) {
                require(previous.manifest == manifest) {
                    "Conflicting BML dependency ${manifest.library}: " +
                        "${previous.manifest.version} in ${previous.artifact} and ${manifest.version} in $artifact"
                }
                continue
            }
            val packageOwner = packages[manifest.generatedPackage]
            require(packageOwner == null) {
                "BML generated package '${manifest.generatedPackage}' is shared by " +
                    "${packageOwner?.manifest?.library} in ${packageOwner?.artifact} and ${manifest.library} in $artifact"
            }
            libraries[manifest.library] = dependency
            packages[manifest.generatedPackage] = dependency
        }
        return libraries.values.toList()
    }

    private fun readManifest(artifact: File): String? = try {
        if (artifact.isDirectory) {
            val manifest = artifact.resolve(BmlLibraryArtifacts.MANIFEST_PATH)
            if (!manifest.exists()) null else manifest.readText()
        } else {
            ZipFile(artifact).use { archive ->
                archive.getEntry(BmlLibraryArtifacts.MANIFEST_PATH)?.let { entry ->
                    require(!entry.isDirectory) { "BML manifest is a directory in $artifact" }
                    archive.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            }
        }
    } catch (failure: IOException) {
        throw IOException("Cannot read BML dependency $artifact: ${failure.message}", failure)
    }
}
