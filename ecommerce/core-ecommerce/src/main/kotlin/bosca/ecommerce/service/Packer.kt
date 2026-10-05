package bosca.ecommerce.service

import bosca.ecommerce.model.Container
import bosca.serialization.UUID
import bosca.service.Service

/**
 * One thing to pack: its shipping footprint (unitless dimensions + weight, matching the company's
 * container units) and how many units. [ref] is an opaque caller key (e.g. a cart line id) the packer
 * echoes back in [PackedItem] so the caller can map placements to its own lines.
 */
data class PackItem(
    val ref: UUID,
    val length: Double,
    val width: Double,
    val height: Double,
    val weight: Double,
    val quantity: Int,
) {
    /** Volume of a single unit; 0 when the item has unknown (zero) dimensions. */
    val unitVolume: Double get() = length * width * height
}

/** One item placement (inside a [PackedBox], or as an unpacked remainder). */
data class PackedItem(val ref: UUID, val quantity: Int)

/** One packed box: the real [containerId] it was packed into (never null) and the units inside it. */
data class PackedBox(val containerId: UUID, val items: List<PackedItem>)

/**
 * The outcome of packing: the [boxes] (each a real container) plus any [unpacked] units that no
 * container could hold (oversize) or that had no container to go in at all. There is no "loose box" —
 * unpacked items are surfaced so the order can be flagged Unable to Package, not silently shipped.
 */
data class PackResult(val boxes: List<PackedBox>, val unpacked: List<PackedItem>)

/**
 * Packs order items into shipping boxes for density. The default [DensityPacker] is a
 * first-fit-decreasing heuristic honoring each container's inner volume + weight capacity; this SPI
 * lets a smarter strategy (or a carrier's own packing) replace it. Pure, deterministic, native-safe.
 * Items that fit no container are returned in [PackResult.unpacked], never as a loose box.
 */
interface Packer : Service {
    fun pack(items: List<PackItem>, containers: List<Container>): PackResult
}
