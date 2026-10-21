package bosca.backup

import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.MetadataService
import bosca.content.model.ContentStorageReference
import bosca.content.model.ContentStorageReferenceKind
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.mockk.coEvery
import io.mockk.mockk
import java.nio.file.Files
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresBackupContentInventoryFileTest {
    @Test
    fun `inventory stages bounded pages in order and preserves typed nullable evidence`(): Unit = runBlocking {
        val directory = createTempDirectory("bosca-reference-pages-")
        val application = BoscaApplication(ApplicationConfig.load("{}".byteInputStream()), ownsServices = false)
        try {
            val first = List(1000) {
                ContentStorageReference(
                    ContentStorageReferenceKind.METADATA, UUID.random(), 2, null, null, null, null, false,
                )
            }
            val legacy = ContentStorageReference(
                ContentStorageReferenceKind.METADATA_VERSION_SUPPLEMENTARY, UUID.random(), 1, null,
                "título", 17, OffsetDateTime.parse("2026-10-20T10:00:00Z"), true,
            )
            val attachment = ContentStorageReference(
                ContentStorageReferenceKind.COLLECTION_SUPPLEMENTARY, UUID.random(), null, UUID.random(),
                "cover", 9, OffsetDateTime.parse("2026-10-20T10:00:00Z"), false,
            )
            val calls = mutableListOf<String>()
            val metadata = mockk<MetadataService>()
            val collections = mockk<CollectionService>()
            coEvery { metadata.getStorageReferences(any(), 1000) } answers {
                val offset = firstArg<Long>()
                calls.add("metadata:$offset")
                when (offset) {
                    0L -> first
                    1000L -> listOf(legacy)
                    1001L -> emptyList()
                    else -> error("Unexpected metadata page: $offset")
                }
            }
            coEvery { collections.getStorageReferences(any(), 1000) } answers {
                val offset = firstArg<Long>()
                calls.add("collections:$offset")
                when (offset) {
                    0L -> listOf(attachment)
                    1L -> emptyList()
                    else -> error("Unexpected collection page: $offset")
                }
            }
            val file = PostgresBackupContentInventoryFile(application.json)
            val inventory = file.write(directory, metadata, collections)
            assertEquals(1002L, inventory.count)
            assertEquals(listOf("metadata:0", "metadata:1000", "metadata:1001", "collections:0", "collections:1"), calls)
            Files.move(directory.resolve("${inventory.path}.tmp"), directory.resolve(inventory.path))
            file.verify(directory, inventory)
            assertEquals(first + legacy + attachment, application.json.decodeFromString(
                ListSerializer(ContentStorageReference.serializer()), directory.resolve(inventory.path).readText(),
            ))
        } finally {
            application.shutdown()
            directory.toFile().deleteRecursively()
        }
    }
}
