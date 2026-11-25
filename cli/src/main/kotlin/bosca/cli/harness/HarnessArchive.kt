package bosca.cli.harness

import bosca.ai.kit.harness.RuntimeArchiveReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.*

/** Opaque native state; all writers must be stopped before exporting. */
internal class HarnessArchive(private val roots: Map<String, Path>, private val policy: String, private val maxBytes: Long) {
    suspend fun export(destination: Path, metadata: JsonObject, secrets: List<String>): RuntimeArchiveReceipt = withContext(Dispatchers.IO) {
        val entries = linkedMapOf<String, HarnessArchiveEntry>()
        var total = 0L
        TarArchiveOutputStream(GZIPOutputStream(Files.newOutputStream(destination))).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            fun add(path: Path, name: String) {
                if (!selected(name, policy)) return
                if (name.substringBefore('/') in setOf("harness", "scratch") &&
                    ((Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Int) and 61440) == 49152) return
                val mode = mode(path)
                val entry = when {
                    path.isSymbolicLink() -> {
                        val target = path.readSymbolicLink().toString()
                        safeLink(name, target)
                        entries[name] = HarnessArchiveEntry("symlink", mode, target = target)
                        TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { linkName = target }
                    }
                    path.isDirectory(NOFOLLOW_LINKS) -> {
                        entries[name] = HarnessArchiveEntry("directory", mode)
                        TarArchiveEntry("$name/")
                    }
                    path.isRegularFile(NOFOLLOW_LINKS) -> {
                        val size = path.fileSize()
                        total = Math.addExact(total, size)
                        require(total <= maxBytes) { "Archive exceeds configured uncompressed limit" }
                        verifyCredentials(path, secrets)
                        entries[name] = HarnessArchiveEntry("file", mode, size, digest(path))
                        TarArchiveEntry(name).apply { this.size = size }
                    }
                    else -> error("Unsupported filesystem entry in state")
                }
                entry.mode = mode
                tar.putArchiveEntry(entry)
                if (entries.getValue(name).kind == "file") Files.newInputStream(path).use { it.copyTo(tar) }
                tar.closeArchiveEntry()
                if (entry.isDirectory) path.listDirectoryEntries().sorted().forEach { add(it, "$name/${it.fileName}") }
            }
            ROOTS.forEach { add(roots.getValue(it), it) }
            val bytes = json.encodeToString(HarnessArchiveManifest.serializer(), HarnessArchiveManifest(policy = policy, metadata = metadata, entries = entries)).toByteArray()
            require(bytes.size <= MANIFEST_LIMIT) { "Archive manifest exceeds format limit" }
            tar.putArchiveEntry(TarArchiveEntry("manifest.json").apply { size = bytes.size.toLong(); mode = 384 })
            tar.write(bytes)
            tar.closeArchiveEntry()
        }
        RuntimeArchiveReceipt(digest(destination), destination.fileSize(), 1, metadata, policy)
    }

    suspend fun restore(archive: Path, checksum: String, sessionId: String, workspaceOnly: Boolean): HarnessArchiveManifest = withContext(Dispatchers.IO) {
        require(digest(archive) == checksum) { "Archive checksum mismatch" }
        val staging = Files.createTempDirectory(archive.parent, "restore-")
        try {
            val found = linkedMapOf<String, HarnessArchiveEntry>()
            var manifest: HarnessArchiveManifest? = null
            var total = 0L
            TarArchiveInputStream(GZIPInputStream(Files.newInputStream(archive))).use { tar ->
                while (true) {
                    val member = tar.nextEntry ?: break
                    val name = member.name.removeSuffix("/")
                    require(member.linkFlag in listOf(TarConstants.LF_NORMAL, TarConstants.LF_OLDNORM, TarConstants.LF_DIR, TarConstants.LF_SYMLINK) && !member.isSparse) { "Unsupported archive entry" }
                    if (name == "manifest.json") {
                        require(manifest == null && member.linkFlag in listOf(TarConstants.LF_NORMAL, TarConstants.LF_OLDNORM) && member.size in 0..MANIFEST_LIMIT.toLong()) { "Invalid archive manifest" }
                        manifest = json.decodeFromString(HarnessArchiveManifest.serializer(), tar.readNBytes(member.size.toInt()).decodeToString())
                        continue
                    }
                    safeName(name)
                    require(name !in found && member.mode and 511 == member.mode) { "Invalid or duplicate archive entry" }
                    total = Math.addExact(total, member.size)
                    require(total <= maxBytes) { "Archive exceeds configured uncompressed limit" }
                    val target = staging.resolve(name)
                    // Links are recorded first and created only after every regular entry is validated.
                    val record = when {
                        member.isSymbolicLink -> {
                            safeLink(name, member.linkName)
                            HarnessArchiveEntry("symlink", member.mode, target = member.linkName)
                        }
                        member.isDirectory -> { target.createDirectories(); HarnessArchiveEntry("directory", member.mode) }
                        member.isFile -> {
                            target.parent.createDirectories()
                            Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW).use { tar.copyTo(it) }
                            require(target.fileSize() == member.size) { "Invalid file size" }
                            HarnessArchiveEntry("file", member.mode, member.size, digest(target))
                        }
                        else -> error("Unsupported archive entry")
                    }
                    found[name] = record
                }
            }
            val verified = requireNotNull(manifest) { "Missing archive manifest" }
            require(verified.formatVersion == 1 && (workspaceOnly || family(verified.policy) == family(policy)) && verified.metadata["sessionId"]?.jsonPrimitive?.content == sessionId) { "Incompatible or foreign archive" }
            require(found == verified.entries) { "Archive members differ from manifest" }
            val links = found.filterValues { it.kind == "symlink" }.keys
            found.keys.forEach { name ->
                require(selected(name, verified.policy)) { "Unselected state in archive" }
                require(generateSequence(Path.of(name).parent) { it.parent }.none { it.toString() in links }) { "Archive entry traverses a symlink" }
            }
            links.forEach { name ->
                val path = staging.resolve(name)
                path.parent.createDirectories()
                Files.createSymbolicLink(path, Path.of(requireNotNull(found.getValue(name).target)))
            }
            ROOTS.forEach { root ->
                val destination = roots.getValue(root)
                require(destination.listDirectoryEntries().isEmpty() && found[root]?.kind == "directory") { "Restore requires empty fixed state roots" }
            }
            try {
                val ordered = found.toSortedMap()
                for (root in if (workspaceOnly) listOf("workspace") else ROOTS) {
                    for ((name, entry) in ordered) {
                        if (!name.startsWith("$root/")) continue
                        val destination = roots.getValue(root).resolve(name.substring(root.length + 1))
                        if (entry.kind == "directory") destination.createDirectories()
                        else {
                            destination.parent.createDirectories()
                            Files.move(staging.resolve(name), destination)
                        }
                    }
                }
                found.toSortedMap(reverseOrder()).forEach { (name, entry) ->
                    val parts = name.split('/', limit = 2)
                    // Fixed mount roots retain deployment permissions and may be owned by the kubelet.
                    if (parts.size > 1 && entry.kind != "symlink" && (!workspaceOnly || parts[0] == "workspace")) {
                        val path = roots.getValue(parts[0]).resolve(parts[1])
                        chmod(path, entry.mode)
                    }
                }
            } catch (failure: java.io.IOException) {
                ROOTS.forEach { root ->
                    val path = roots.getValue(root)
                    path.listDirectoryEntries().forEach(::removeTree)
                }
                throw failure
            }
            verified
        } finally { removeTree(staging) }
    }

    companion object {
        val ROOTS = listOf("workspace", "harness", "scratch")
        private const val MANIFEST_LIMIT = 16 * 1024 * 1024
        private val json = Json { encodeDefaults = true; explicitNulls = false }
        /** Native state formats belong to the harness; its next version opens and migrates its own directory. */
        fun family(policy: String): String = policy.substringBefore('-').also {
            require(it in setOf("codex", "claude") && policy.startsWith("$it-") && policy.length > it.length + 1) { "Unsupported harness state policy" }
        }
        fun digest(path: Path): String = Files.newInputStream(path).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024 * 1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            digest.digest().toHexString()
        }
        private fun selected(name: String, policy: String): Boolean {
            val parts = name.split('/')
            if (parts[0] !in ROOTS) return false
            if (parts[0] != "harness" || parts.size == 1) return true
            // Provider authentication is injected afresh; the rest of the operational directory is opaque.
            val authentication = when (family(policy)) {
                "codex" -> "auth.json"
                else -> ".credentials.json"
            }
            return parts[1] != authentication
        }
        private fun safeName(name: String): Path = Path.of(name).also {
            require(name.isNotEmpty() && !it.isAbsolute && it.none { part -> part.toString() == ".." } && it.normalize().toString() == name && it.first().toString() in ROOTS) { "Invalid archive path" }
        }
        private fun safeLink(name: String, target: String) {
            require(target.isNotEmpty() && !Path.of(target).isAbsolute) { "Archive symlink must be relative" }
            val resolved = safeName(Path.of(name).parent.resolve(target).normalize().toString())
            require(resolved.first() == Path.of(name).first()) { "Archive link crosses fixed mount roots" }
        }
        private fun verifyCredentials(path: Path, secrets: List<String>) {
            val values = secrets.filter(String::isNotEmpty).map(String::toByteArray)
            if (values.isEmpty()) return
            val overlap = values.maxOf { it.size } - 1
            Files.newInputStream(path).use { input ->
                var tail = byteArrayOf()
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val bytes = tail + buffer.copyOf(count)
                    require(values.none { value -> (0..bytes.size - value.size).any { at -> value.indices.all { bytes[at + it] == value[it] } } }) { "Credential material found in retained state; refusing checkpoint" }
                    tail = bytes.takeLast(overlap).toByteArray()
                }
            }
        }
        fun mode(path: Path): Int = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).sumOf { 1 shl (8 - it.ordinal) }
        fun chmod(path: Path, mode: Int) { Files.setPosixFilePermissions(path, PosixFilePermission.entries.filter { mode and (1 shl (8 - it.ordinal)) != 0 }.toSet()) }
        fun removeTree(path: Path) {
            if (path.isDirectory(NOFOLLOW_LINKS)) { chmod(path, 448); path.listDirectoryEntries().forEach(::removeTree) }
            Files.deleteIfExists(path)
        }
    }
}
