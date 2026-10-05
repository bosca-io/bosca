package bosca.pipelines.testutil

import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import io.mockk.coEvery
import io.mockk.mockk
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * A relaxed [ObjectStorageService] backed by an in-memory map for the blob ops the pipeline run uses
 * ([ObjectStorageService.setInputStream]/[ObjectStorageService.getString]/[ObjectStorageService.delete],
 * keyed by the [ObjectPath]'s string form). A read of an absent key throws [FileNotFoundException],
 * mirroring a real backend (the run treats a genuinely-missing staged object as an error, not "no
 * output"). The content-addressed methods are unused here and left relaxed.
 */
fun inMemoryObjectStorage(): ObjectStorageService {
    val blobs = ConcurrentHashMap<String, ByteArray>()
    return mockk(relaxed = true) {
        coEvery { setInputStream(any(), any(), any()) } answers {
            val bytes = secondArg<InputStream>().readBytes()
            blobs[firstArg<ObjectPath>().toString()] = bytes
            bytes.size.toLong()
        }
        coEvery { getString(any()) } answers {
            val key = firstArg<ObjectPath>().toString()
            blobs[key]?.toString(Charsets.UTF_8) ?: throw FileNotFoundException(key)
        }
        coEvery { delete(any()) } answers {
            blobs.remove(firstArg<ObjectPath>().toString())
            Unit
        }
    }
}
