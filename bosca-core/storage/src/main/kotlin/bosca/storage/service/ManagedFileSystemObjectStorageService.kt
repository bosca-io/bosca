package bosca.storage.service

import bosca.di.ObjectProvider
import bosca.security.service.SecurityService
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.ZoneOffset
import java.util.PriorityQueue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Adds inventory access and resource ownership to the existing storage implementation. */
class ManagedFileSystemObjectStorageService(
    urlPrefix: String,
    urlUploadPrefix: String,
    urlSigner: ObjectProvider<UrlSigner>,
    private val basePath: String,
    securityService: ObjectProvider<SecurityService>,
) : ManagedObjectStorageService, ObjectStorageService by FileSystemObjectStorageService(urlPrefix, urlUploadPrefix, urlSigner, basePath, securityService) {
    override suspend fun listObjects(prefix: String, offset: Long, limit: Int): List<ObjectStorageEntry> {
        require(offset >= 0) { "Object listing offset must not be negative" }
        require(limit > 0) { "Object listing limit must be positive" }
        return withContext(StorageDispatcher) {
            val context = currentCoroutineContext()
            val root = StringObjectPath("").toFile(basePath).toPath().normalize()
            check(Files.readAttributes(root, BasicFileAttributes::class.java).isDirectory) {
                "Object storage root must be a directory: $root"
            }
            val window = if (offset > Long.MAX_VALUE - limit) Long.MAX_VALUE else offset + limit
            // Traversal order is unspecified; retain only the earliest offset + limit keys.
            val entries = PriorityQueue<ObjectStorageEntry>(compareByDescending { it.key })
            fun key(path: Path) = root.relativize(path).toString().replace(File.separatorChar, '/')
            fun containsScope(path: Path): Boolean {
                if (path == root) return true
                val directory = key(path) + "/"
                return directory.startsWith(prefix) || prefix.startsWith(directory)
            }
            Files.walkFileTree(
                root,
                setOf(FileVisitOption.FOLLOW_LINKS),
                Int.MAX_VALUE,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        context.ensureActive()
                        return if (containsScope(dir)) FileVisitResult.CONTINUE else FileVisitResult.SKIP_SUBTREE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        context.ensureActive()
                        val key = key(file)
                        if (!key.startsWith(prefix)) return FileVisitResult.CONTINUE
                        val observed = if (attrs.isSymbolicLink) {
                            Files.readAttributes(file, BasicFileAttributes::class.java)
                        } else {
                            attrs
                        }
                        if (observed.isRegularFile) {
                            val entry = ObjectStorageEntry(key, objectInfo(observed))
                            if (entries.size.toLong() < window) {
                                entries.add(entry)
                            } else if (entry.key < entries.peek().key) {
                                entries.poll()
                                entries.add(entry)
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        context.ensureActive()
                        if (key(file).startsWith(prefix) || containsScope(file)) throw exc
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            if (offset >= entries.size.toLong()) {
                emptyList()
            } else {
                entries.sortedBy { it.key }.drop(offset.toInt()).take(limit)
            }
        }
    }

    override suspend fun getObjectInfo(path: ObjectPath): ObjectStorageInfo = withContext(StorageDispatcher) {
        val attributes = try {
            Files.readAttributes(path.toFile(basePath).toPath(), BasicFileAttributes::class.java)
        } catch (failure: NoSuchFileException) {
            throw ObjectNotFoundException(path, failure)
        }
        if (!attributes.isRegularFile) throw ObjectNotFoundException(path)
        objectInfo(attributes)
    }

    private fun objectInfo(attributes: BasicFileAttributes) = ObjectStorageInfo(
        length = attributes.size(),
        modified = attributes.lastModifiedTime().toInstant().atOffset(ZoneOffset.UTC),
    )

}
