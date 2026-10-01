@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.ecommerce.service

import bosca.ecommerce.model.Container
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**the default density (first-fit-decreasing) packing strategy. */
class DensityPackerTest {

    private val packer = DensityPacker()

    private val smallId = UUID.random()
    private val largeId = UUID.random()

    /** small: inner 10×10×10 = 1000 volume, 5 weight. large: 20×20×20 = 8000 volume, 50 weight. */
    private fun container(id: UUID, dim: Double, wt: Double) = Container(
        id = id, companyId = UUID.random(), name = "c",
        width = dim, height = dim, length = dim, weight = 0.0,
        supportedWidth = dim, supportedHeight = dim, supportedLength = dim, supportedWeight = wt,
    )
    private val small get() = container(smallId, 10.0, 5.0)
    private val large get() = container(largeId, 20.0, 50.0)

    private fun item(ref: UUID, side: Double, weight: Double, qty: Int) =
        PackItem(ref = ref, length = side, width = side, height = side, weight = weight, quantity = qty)

    @Test
    fun `a single small item packs into the smallest fitting container`() {
        val ref = UUID.random()
        val result = packer.pack(listOf(item(ref, 5.0, 1.0, 1)), listOf(small, large))
        assertEquals(1, result.boxes.size)
        assertEquals(smallId, result.boxes.single().containerId)
        assertEquals(listOf(PackedItem(ref, 1)), result.boxes.single().items)
        assertTrue(result.unpacked.isEmpty())
    }

    @Test
    fun `units overflow into additional boxes when a container's volume fills`() {
        val ref = UUID.random()
        // 125-volume units, negligible weight: a small box (1000 vol) holds 8, so 10 units → 2 boxes.
        val result = packer.pack(listOf(item(ref, 5.0, 0.1, 10)), listOf(small, large))
        assertEquals(2, result.boxes.size)
        assertEquals(10, result.boxes.sumOf { b -> b.items.sumOf { it.quantity } })
        assertTrue(result.boxes.all { it.containerId == smallId })
        assertTrue(result.unpacked.isEmpty())
    }

    @Test
    fun `weight capacity splits boxes even when volume is ample`() {
        val ref = UUID.random()
        // 1-volume units but 3 weight each; small caps weight at 5 → one unit per box → 4 boxes.
        val result = packer.pack(listOf(item(ref, 1.0, 3.0, 4)), listOf(small))
        assertEquals(4, result.boxes.size)
        assertTrue(result.unpacked.isEmpty())
    }

    @Test
    fun `an item larger than every container is unpacked, never a loose box`() {
        val ref = UUID.random()
        val result = packer.pack(listOf(item(ref, 30.0, 1.0, 1)), listOf(small, large)) // 27000 > 8000
        assertTrue(result.boxes.isEmpty())
        assertEquals(listOf(PackedItem(ref, 1)), result.unpacked)
    }

    @Test
    fun `with no containers the whole order is unpacked, never a loose box`() {
        val ref = UUID.random()
        val result = packer.pack(listOf(item(ref, 5.0, 1.0, 3)), emptyList())
        assertTrue(result.boxes.isEmpty())
        assertEquals(listOf(PackedItem(ref, 3)), result.unpacked)
    }

    @Test
    fun `zero-dimension items pack by weight alone`() {
        val ref = UUID.random()
        // No volume, 2 weight each; small caps weight at 5 → 2 per box → 3 units = 2 boxes.
        val result = packer.pack(listOf(item(ref, 0.0, 2.0, 3)), listOf(small))
        assertEquals(2, result.boxes.size)
        assertEquals(3, result.boxes.sumOf { b -> b.items.sumOf { it.quantity } })
        assertTrue(result.boxes.all { it.containerId == smallId })
        assertTrue(result.unpacked.isEmpty())
    }

    @Test
    fun `the packable units box up and only the oversize remainder is unpacked`() {
        val fits = UUID.random()
        val oversize = UUID.random()
        val result = packer.pack(
            listOf(item(fits, 5.0, 1.0, 1), item(oversize, 30.0, 1.0, 2)),
            listOf(small, large),
        )
        assertEquals(listOf(PackedItem(fits, 1)), result.boxes.single().items)
        assertEquals(smallId, result.boxes.single().containerId)
        assertEquals(listOf(PackedItem(oversize, 2)), result.unpacked)
    }

    @Test
    fun `packing is deterministic for the same input`() {
        val items = listOf(item(UUID.random(), 5.0, 1.0, 4), item(UUID.random(), 8.0, 2.0, 2))
        val containers = listOf(small, large)
        assertEquals(packer.pack(items, containers), packer.pack(items, containers))
    }

    @Test
    fun `empty input packs nothing and reports nothing unpacked`() {
        // The `present.isEmpty()` early return: no positive-quantity items -> both lists empty.
        val result = packer.pack(emptyList(), listOf(small, large))
        assertTrue(result.boxes.isEmpty())
        assertTrue(result.unpacked.isEmpty())
    }

    @Test
    fun `items with non-positive quantity are filtered out before packing`() {
        // A zero-quantity item leaves nothing present -> the empty early return, never a box.
        val result = packer.pack(listOf(item(UUID.random(), 5.0, 1.0, 0)), listOf(small, large))
        assertTrue(result.boxes.isEmpty())
        assertTrue(result.unpacked.isEmpty())
    }

    @Test
    fun `a container with zero weight capacity is not a usable type`() {
        // weightCap = 0 fails the `weightCap > 0.0` filter; with no other usable type the order is unpacked.
        val ref = UUID.random()
        val zeroWeight = container(UUID.random(), 10.0, 0.0)
        val result = packer.pack(listOf(item(ref, 5.0, 1.0, 1)), listOf(zeroWeight))
        assertTrue(result.boxes.isEmpty())
        assertEquals(listOf(PackedItem(ref, 1)), result.unpacked)
    }

    @Test
    fun `a container with zero volume capacity is filtered, leaving a larger one to pack into`() {
        // dim 0 -> volumeCap 0 -> filtered by `volumeCap > 0.0`; only `large` remains usable.
        val ref = UUID.random()
        val zeroVolume = container(UUID.random(), 0.0, 5.0)
        val result = packer.pack(listOf(item(ref, 5.0, 1.0, 1)), listOf(zeroVolume, large))
        assertEquals(1, result.boxes.size)
        assertEquals(largeId, result.boxes.single().containerId)
        assertTrue(result.unpacked.isEmpty())
    }

    @Test
    fun `a unit too big for the smallest container is placed in a larger fitting one`() {
        // 15-side (3375 volume) exceeds the small box (1000) but fits the large box (8000): the new-box
        // search skips `small` and selects `large` (the `firstOrNull { fits }` over the sorted types).
        val ref = UUID.random()
        val result = packer.pack(listOf(item(ref, 15.0, 1.0, 1)), listOf(small, large))
        assertEquals(1, result.boxes.size)
        assertEquals(largeId, result.boxes.single().containerId)
        assertTrue(result.unpacked.isEmpty())
    }

    @Test
    fun `a unit that fits by volume but exceeds every container's weight cap is unpacked`() {
        // side 5 -> volume 125 (<= small's 1000) but weight 10 (> small's 5): the type search's
        // `volume <= cap && weight <= cap` has its first operand true and its second false, so no
        // container is chosen and the unit is surfaced as unpacked rather than boxed.
        val ref = UUID.random()
        val result = packer.pack(listOf(item(ref, 5.0, 10.0, 1)), listOf(small))
        assertTrue(result.boxes.isEmpty())
        assertEquals(listOf(PackedItem(ref, 1)), result.unpacked)
    }

    @Test
    fun `accumulates multiple oversize units of the same item into the unpacked count`() {
        // Three units, each too big for any container -> the `unpacked[ref] ?: 0` path runs first-then-accumulate.
        val ref = UUID.random()
        val result = packer.pack(listOf(item(ref, 30.0, 1.0, 3)), listOf(small, large))
        assertTrue(result.boxes.isEmpty())
        assertEquals(listOf(PackedItem(ref, 3)), result.unpacked)
    }
}
