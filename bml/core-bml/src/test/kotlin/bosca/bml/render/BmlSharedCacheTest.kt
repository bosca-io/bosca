package bosca.bml.render

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class BmlSharedCacheTest {
    private data class Item(
        val etag: String?,
        val modified: Instant?,
    )

    @Test
    fun `metadata revision uses its etag and modified instant`() {
        val modified = Instant.parse("2026-09-16T12:00:00Z")

        assertEquals(
            BmlSharedCacheRevision("metadata-etag", modified),
            BmlSharedCacheRevision.fromMetadata("metadata-etag", modified),
        )
        assertNull(BmlSharedCacheRevision.fromMetadata(null, modified))
        assertNull(BmlSharedCacheRevision.fromMetadata("", modified))
    }

    @Test
    fun `list revision uses the newest modified item and its etag`() {
        val revision = BmlSharedCacheRevision.fromNewestModified(
            items = listOf(
                Item("older", Instant.parse("2026-09-15T12:00:00Z")),
                Item("newest", Instant.parse("2026-09-16T12:00:00Z")),
            ),
            etag = Item::etag,
            lastModified = Item::modified,
        )

        assertEquals(
            BmlSharedCacheRevision("newest", Instant.parse("2026-09-16T12:00:00Z")),
            revision,
        )
    }

    @Test
    fun `list revision includes every item tied for newest modified`() {
        val modified = Instant.parse("2026-09-16T12:00:00Z")
        val original = BmlSharedCacheRevision.fromNewestModified(
            items = listOf(Item("z", modified), Item("a", modified)),
            etag = Item::etag,
            lastModified = Item::modified,
        )
        val changed = BmlSharedCacheRevision.fromNewestModified(
            items = listOf(Item("z", modified), Item("b", modified)),
            etag = Item::etag,
            lastModified = Item::modified,
        )
        val reordered = BmlSharedCacheRevision.fromNewestModified(
            items = listOf(Item("a", modified), Item("z", modified)),
            etag = Item::etag,
            lastModified = Item::modified,
        )

        assertNotEquals(original, changed)
        assertEquals(original, reordered)
    }

    @Test
    fun `list revision is absent without an item carrying both validator values`() {
        assertNull(
            BmlSharedCacheRevision.fromNewestModified(
                items = listOf(Item(null, Instant.EPOCH), Item("etag", null)),
                etag = Item::etag,
                lastModified = Item::modified,
            ),
        )
    }

    @Test
    fun `list revision is absent when any item lacks a validator value`() {
        assertNull(
            BmlSharedCacheRevision.fromNewestModified(
                items = listOf(
                    Item("complete", Instant.EPOCH),
                    Item("missing-modified", null),
                ),
                etag = Item::etag,
                lastModified = Item::modified,
            ),
        )
        assertNull(
            BmlSharedCacheRevision.fromNewestModified(
                items = listOf(
                    Item("complete", Instant.EPOCH),
                    Item(null, Instant.EPOCH.plusSeconds(1)),
                ),
                etag = Item::etag,
                lastModified = Item::modified,
            ),
        )
    }
}
