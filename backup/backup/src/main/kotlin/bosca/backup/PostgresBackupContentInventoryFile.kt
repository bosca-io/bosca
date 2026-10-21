package bosca.backup

import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.MetadataService
import bosca.content.model.ContentStorageReference
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence

/** Stages and verifies bounded reference metadata; it never reads asset bodies. */
internal class PostgresBackupContentInventoryFile(private val json: Json) {
    suspend fun write(
        directory: Path,
        metadata: MetadataService,
        collections: CollectionService,
    ): PostgresBackupContentInventory {
        val context = currentCoroutineContext()
        val file = directory.resolve("content-references.json.tmp")
        var count = 0L
        Files.newBufferedWriter(file, CREATE_NEW, WRITE).use { writer ->
            writer.write("[")
            suspend fun appendPages(read: suspend (Long, Int) -> List<ContentStorageReference>) {
                var offset = 0L
                while (true) {
                    context.ensureActive()
                    val page = read(offset, 1000)
                    if (page.isEmpty()) break
                    for (reference in page) {
                        context.ensureActive()
                        if (count > 0) writer.write(",")
                        writer.write("\n")
                        writer.write(json.encodeToString(ContentStorageReference.serializer(), reference))
                        count++
                    }
                    offset += page.size
                }
            }
            appendPages(metadata::getStorageReferences)
            appendPages(collections::getStorageReferences)
            writer.write("\n]")
        }
        val length = Files.size(file)
        val sha256 = BackupPayloadInputStream(Files.newInputStream(file), length, file.fileName.toString(), context).use {
            it.transferTo(OutputStream.nullOutputStream())
            it.verifyComplete()
        }
        return PostgresBackupContentInventory(count, length, sha256).also { it.validate() }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun verify(directory: Path, inventory: PostgresBackupContentInventory) {
        inventory.validate()
        val file = directory.resolve(inventory.path)
        require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Content-reference inventory must be a regular file" }
        val context = currentCoroutineContext()
        // Verify bytes before decoding so corrupt retained content reports the same integrity failure
        // as other artifact components, even when the corruption also makes its JSON invalid.
        BackupPayloadInputStream(Files.newInputStream(file), inventory.length, inventory.path, context).use { input ->
            input.transferTo(OutputStream.nullOutputStream())
            if (input.verifyComplete() != inventory.sha256) {
                throw BackupPayloadMismatchException("Content-reference inventory SHA-256 mismatch")
            }
        }
        var count = 0L
        Files.newInputStream(file).use { input ->
            for (reference in json.decodeToSequence(input, ContentStorageReference.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)) {
                context.ensureActive()
                count++
            }
        }
        require(count == inventory.count) { "Content-reference inventory count mismatch: expected ${inventory.count}, found $count" }
    }
}
