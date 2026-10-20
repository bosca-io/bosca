package bosca.storage.service

import bosca.di.ObjectProvider
import bosca.security.service.SecurityService
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.ZoneOffset
import java.util.Objects
import java.util.PriorityQueue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.updateAndFetch
import kotlin.math.min

fun ObjectPath.toFile(basePath: String): File {
    val pathname = "$basePath/$this"
    if (pathname.contains("..")) error("Path traversal attempt detected: $pathname")
    return File(pathname).absoluteFile
}

class FileSystemObjectStorageService(
    urlPrefix: String,
    urlUploadPrefix: String,
    urlSigner: ObjectProvider<UrlSigner>,
    private val basePath: String,
    securityService: ObjectProvider<SecurityService>
) : AbstractObjectStorageService(urlPrefix, urlUploadPrefix, urlSigner, securityService) {

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

    override suspend fun getInputStream(path: ObjectPath): InputStream = openOnStorageDispatcher {
        val file = path.toFile(basePath)
        try {
            file.inputStream()
        } catch (e: FileNotFoundException) {
            if (file.exists()) throw e
            throw ObjectNotFoundException(path, e)
        }
    }

    override suspend fun getInputStream(path: ObjectPath, version: String): InputStream {
        require(version.isNotBlank()) { "An object version is required" }
        throw UnsupportedOperationException("Filesystem storage does not retain object versions")
    }

    private fun getOutputStream(path: ObjectPath): OutputStream {
        val file = path.toFile(basePath)
        file.parentFile.mkdirs()
        return file.outputStream()
    }

    override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long = withContext(StorageDispatcher) {
        getOutputStream(path).use { stream.copyTo(it) }
    }

    @OptIn(ExperimentalAtomicApi::class)
    override suspend fun getInputStreamRange(
        path: ObjectPath,
        range: LongRange
    ): InputStream = openOnStorageDispatcher {
        // Opened, sized and positioned in one step, so the file is closed if any of it fails, and
        // (through openOnStorageDispatcher) if the caller is cancelled before taking the stream.
        val file = path.toFile(basePath)
        val raf = try {
            RandomAccessFile(file, "r")
        } catch (e: FileNotFoundException) {
            if (file.exists()) throw e
            throw ObjectNotFoundException(path, e)
        }
        try {
            val fileLength = raf.length()
            val effectiveLast = min(range.last, fileLength - 1)
            val length = if (range.first > effectiveLast) 0L else effectiveLast - range.first + 1
            raf.seek(range.first)
            rangeStream(raf, AtomicLong(length))
        } catch (e: Throwable) {
            raf.close()
            throw e
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun rangeStream(raf: RandomAccessFile, available: AtomicLong): InputStream =
        object : InputStream() {
            override fun available(): Int = available.load().toInt()

            override fun read(): Int {
                if (available.load() <= 0) {
                    return -1
                }
                val value = raf.read()
                if (value == -1) {
                    available.store(0)
                } else {
                    available.decrementAndFetch()
                }
                return value
            }

            override fun read(b: ByteArray): Int = read(b, 0, b.size)

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Objects.checkFromIndexSize(off, len, b.size)
                if (len == 0) return 0
                val avail = available.load()
                if (avail <= 0) {
                    return -1
                }
                val length = min(avail, len.toLong()).toInt()
                val r = raf.read(b, off, length)
                if (r == -1) {
                    available.store(0)
                } else {
                    available.updateAndFetch { it - r }
                }
                return r
            }

            override fun close() {
                raf.close()
                super.close()
            }
        }

    override suspend fun delete(path: ObjectPath) {
        val file = path.toFile(basePath)
        file.delete()
    }
}
