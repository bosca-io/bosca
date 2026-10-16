package bosca.bml.gradle

import groovy.json.JsonException
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Up-to-date tracking for project files a library asset bundle read outside its authored roots.
 *
 * The bundler writes the files it read to a record. After a run, [store] saves content digests of
 * those files and of every file beside them, so a new sibling that could change import resolution is
 * noticed. [isCurrent] recomputes the same snapshot before the next run. Files under `trackedRoots`,
 * installed packages, and build output are excluded: the task already declares them as ordinary
 * inputs, whose hashes Gradle caches instead of re-reading them on every build.
 */
internal object BmlLibraryTrackedFiles {
    /** Whether [stored] was written by [store] for the same record and every tracked file is unchanged. */
    fun isCurrent(stored: File, record: File, buildDirectory: File, trackedRoots: List<File>): Boolean {
        val current = snapshot(record, buildDirectory, trackedRoots) ?: return false
        if (!stored.isFile) return false
        val previous = try {
            JsonSlurper().parse(stored) as? Map<*, *>
        } catch (_: JsonException) {
            // A damaged snapshot only costs a rebuild.
            null
        }
        return previous == current
    }

    /** Saves the digests for [record], or removes [stored] when the bundler wrote no usable record. */
    fun store(stored: File, record: File, buildDirectory: File, trackedRoots: List<File>) {
        val current = snapshot(record, buildDirectory, trackedRoots)
        if (current == null) {
            stored.delete()
            return
        }
        stored.parentFile.mkdirs()
        stored.writeText(JsonOutput.toJson(current))
    }

    private fun snapshot(record: File, buildDirectory: File, trackedRoots: List<File>): Map<String, String>? {
        if (!record.isFile) return null
        val paths = try {
            (JsonSlurper().parse(record) as? List<*>)?.filterIsInstance<String>()
        } catch (_: JsonException) {
            null
        } ?: return null
        val buildRoot = buildDirectory.canonicalFile.toPath()
        // Match both spellings so linked roots and files reported at their real location are recognized.
        val roots = trackedRoots.flatMap { listOf(it.absoluteFile.normalize().toPath(), it.canonicalFile.toPath()) }.distinct()
        val files = sortedSetOf<File>()
        for (path in paths) {
            val file = File(path).absoluteFile.normalize()
            if (file.toPath().any { it.toString() == "node_modules" }) continue
            val canonical = file.canonicalFile.toPath()
            if (canonical.startsWith(buildRoot)) continue
            if (roots.any { file.toPath().startsWith(it) || canonical.startsWith(it) }) continue
            files += file
            file.parentFile?.listFiles()?.filterTo(files) { it.isFile }
        }
        return files.associateTo(linkedMapOf()) { it.path to digest(it) }
    }

    private fun digest(file: File): String =
        if (file.isFile) HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes())) else "missing"
}
