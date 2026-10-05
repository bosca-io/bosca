package bosca.ecommerce.service

import bosca.ecommerce.model.Container
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Default [Packer]: first-fit-decreasing by volume, honoring each container's inner volume and weight
 * capacity.
 *
 * Units are placed largest-first into the first open box that still has room; a fresh box uses the
 * smallest container that fits the unit. Every box is a real container — an item too big for any
 * container (oversize), or any item when the company has defined no containers, is returned in
 * [PackResult.unpacked] (never a loose box) so the order can be flagged Unable to Package. Zero-dimension
 * items (unknown size) take no volume, so they pack by weight alone. Deterministic and native-safe.
 */
@ServiceImplementation
class DensityPacker : Packer {

    override fun pack(items: List<PackItem>, containers: List<Container>): PackResult {
        val present = items.filter { it.quantity > 0 }
        if (present.isEmpty()) return PackResult(emptyList(), emptyList())

        // Usable container types, smallest inner volume first so a new box uses the tightest fit.
        val types = containers
            .map { ContainerType(it.id, it.supportedWidth * it.supportedHeight * it.supportedLength, it.supportedWeight) }
            .filter { it.volumeCap > 0.0 && it.weightCap > 0.0 }
            .sortedWith(compareBy({ it.volumeCap }, { it.weightCap }, { it.id.toString() }))

        // No usable catalog → nothing can be packed; the whole order is unpackable.
        if (types.isEmpty()) return PackResult(emptyList(), present.map { PackedItem(it.ref, it.quantity) })

        // Expand to individual units, largest-first (first-fit-decreasing); deterministic tie-breaks.
        val units = present
            .flatMap { item -> List(item.quantity) { PackUnit(item.ref, item.unitVolume, item.weight) } }
            .sortedWith(compareByDescending<PackUnit> { it.volume }.thenByDescending { it.weight }.thenBy { it.ref.toString() })

        val open = mutableListOf<OpenBox>()
        val unpacked = LinkedHashMap<UUID, Int>()
        for (u in units) {
            val existing = open.firstOrNull { it.fits(u.volume, u.weight) }
            if (existing != null) {
                existing.add(u.ref, u.volume, u.weight)
                continue
            }
            val type = types.firstOrNull { u.volume <= it.volumeCap + EPS && u.weight <= it.weightCap + EPS }
            if (type == null) {
                // Too big for any container — not boxed; surfaced as unpackable, never a loose box.
                unpacked[u.ref] = (unpacked[u.ref] ?: 0) + 1
                continue
            }
            open.add(OpenBox(type.id, type.volumeCap, type.weightCap).also { it.add(u.ref, u.volume, u.weight) })
        }
        return PackResult(
            boxes = open.map { box -> PackedBox(box.containerId, box.items.map { (ref, qty) -> PackedItem(ref, qty) }) },
            unpacked = unpacked.map { (ref, qty) -> PackedItem(ref, qty) },
        )
    }

    private class ContainerType(val id: UUID, val volumeCap: Double, val weightCap: Double)

    private class PackUnit(val ref: UUID, val volume: Double, val weight: Double)

    private class OpenBox(val containerId: UUID, private val volumeCap: Double, private val weightCap: Double) {
        private var usedVolume = 0.0
        private var usedWeight = 0.0
        val items = LinkedHashMap<UUID, Int>()
        fun fits(volume: Double, weight: Double): Boolean =
            usedVolume + volume <= volumeCap + EPS && usedWeight + weight <= weightCap + EPS
        fun add(ref: UUID, volume: Double, weight: Double) {
            usedVolume += volume
            usedWeight += weight
            items[ref] = (items[ref] ?: 0) + 1
        }
    }

    private companion object {
        const val EPS = 1e-9
    }
}
