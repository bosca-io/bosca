package bosca.storage.service

import bosca.di.ObjectProvider
import bosca.security.service.SecurityService
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Objects
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

    override suspend fun getInputStream(path: ObjectPath): InputStream = openOnStorageDispatcher {
        val file = path.toFile(basePath)
        try {
            file.inputStream()
        } catch (e: FileNotFoundException) {
            if (file.exists()) throw e
            throw ObjectNotFoundException(path, e)
        }
    }

    private fun getOutputStream(path: ObjectPath): OutputStream {
        val file = path.toFile(basePath)
        file.parentFile.mkdirs()
        return file.outputStream()
    }

    override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
        return getOutputStream(path).use { stream.copyTo(it) }
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
