package bosca.git.dfs

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Value semantics for the DFS pack metadata models: every field participates in
 * equality (a copy differing in exactly one field must not be equal), hashCode
 * covers the nullable arms, and identity/type mismatches behave.
 */
class DfsModelsTest {

    private val base = DfsPack(
        id = UUID.random(),
        repositoryId = UUID.random(),
        packName = "pack-1",
        packSource = "INSERT",
        fileSize = 1L,
        objectCount = 2L,
        deltaCount = 3L,
        minUpdateIdx = 4L,
        maxUpdateIdx = 5L,
        committed = true,
        gcRetained = false,
        created = OffsetDateTime.now(),
        deletedAt = null,
    )

    @Test
    fun `every field participates in pack equality`() {
        assertEquals(base, base.copy())
        assertEquals(base.hashCode(), base.copy().hashCode())
        assertEquals(base, base) // identity arm
        assertNotEquals<Any>(base, "not a pack") // type arm

        val variants = listOf(
            base.copy(id = UUID.random()),
            base.copy(repositoryId = UUID.random()),
            base.copy(packName = "other"),
            base.copy(packSource = "GC"),
            base.copy(fileSize = 9L),
            base.copy(objectCount = 9L),
            base.copy(deltaCount = 9L),
            base.copy(minUpdateIdx = 9L),
            base.copy(maxUpdateIdx = 9L),
            base.copy(committed = false),
            base.copy(gcRetained = true),
            base.copy(created = base.created.plusDays(1)),
            base.copy(deletedAt = OffsetDateTime.now()),
        )
        for ((i, variant) in variants.withIndex()) {
            assertNotEquals(base, variant, "field #$i must affect equality")
        }
    }

    @Test
    fun `hashCode covers both deletedAt arms`() {
        base.hashCode()
        base.copy(deletedAt = OffsetDateTime.now()).hashCode()
        assertNotEquals(base.hashCode(), base.copy(packName = "x").hashCode())
    }

    @Test
    fun `pack extension equality covers every field`() {
        val ext = DfsPackExtension(
            packId = UUID.random(), extension = "pack", fileSize = 10L,
            storagePath = "git/x/packs/p.pack", created = OffsetDateTime.now(),
        )
        assertEquals(ext, ext.copy())
        assertEquals(ext, ext)
        assertNotEquals<Any>(ext, 42)

        val variants = listOf(
            ext.copy(packId = UUID.random()),
            ext.copy(extension = "idx"),
            ext.copy(fileSize = 11L),
            ext.copy(storagePath = "other"),
            ext.copy(created = ext.created.plusDays(1)),
        )
        for ((i, variant) in variants.withIndex()) {
            assertNotEquals(ext, variant, "field #$i must affect equality")
        }
        ext.hashCode()
    }

    @Test
    fun `packs and extensions serialize round-trip with contextual serializers`() {
        val json = kotlinx.serialization.json.Json {
            serializersModule = kotlinx.serialization.modules.SerializersModule {
                contextual(kotlin.uuid.Uuid::class, kotlinx.serialization.serializer<kotlin.uuid.Uuid>())
                contextual(java.time.OffsetDateTime::class, bosca.serialization.OffsetDateTimeSerializer())
            }
        }
        val jsonDefaults = kotlinx.serialization.json.Json(json) { encodeDefaults = true }

        // Sparse (defaults omitted) and full (defaults encoded) round-trips cover
        // both arms of every field's write/skip and decode/mask branches.
        for (encoder in listOf(json, jsonDefaults)) {
            val packText = encoder.encodeToString(DfsPack.serializer(), base)
            assertEquals(base, json.decodeFromString(DfsPack.serializer(), packText))

            val full = base.copy(deletedAt = OffsetDateTime.now().withNano(0))
            assertEquals(full, json.decodeFromString(DfsPack.serializer(), encoder.encodeToString(DfsPack.serializer(), full)))

            val ext = DfsPackExtension(packId = UUID.random(), extension = "pack", fileSize = 1L, storagePath = "p", created = OffsetDateTime.now().withNano(0))
            assertEquals(ext, json.decodeFromString(DfsPackExtension.serializer(), encoder.encodeToString(DfsPackExtension.serializer(), ext)))
        }

        // Decoding with only required fields exercises the default-mask arms.
        val minimal = json.decodeFromString(
            DfsPack.serializer(),
            """{"repositoryId":"${base.repositoryId}","packName":"p"}""",
        )
        assertEquals("p", minimal.packName)
        assertEquals(0L, minimal.fileSize)
        assertEquals(null, minimal.deletedAt)
    }
}
