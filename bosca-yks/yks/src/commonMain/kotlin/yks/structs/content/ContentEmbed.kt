package yks.structs.content

import yks.lib0.jsonParse
import yks.lib0.jsonStringify
import yks.structs.Item
import yks.utils.Transaction
import yks.utils.UpdateDecoder
import yks.utils.UpdateEncoder

/**
 * Content holding an embedded object (e.g., inline image).
 * Ref = 5. Length = 1. Countable. Not splittable or mergeable.
 * Matches yjs ContentEmbed.
 */
class ContentEmbed(val embed: Any?) : AbstractContent {
    override fun getLength(): Int = 1
    override fun getContent(): List<Any?> = listOf(embed)
    override fun isCountable(): Boolean = true
    override fun copy(): AbstractContent = ContentEmbed(embed)

    override fun splice(offset: Int): AbstractContent {
        throw UnsupportedOperationException("ContentEmbed cannot be split")
    }

    override fun mergeWith(right: AbstractContent): Boolean = false

    override fun integrate(transaction: Transaction, item: Item) {}
    override fun delete(transaction: Transaction) {}
    override fun gc(transaction: Transaction) {}

    override fun write(encoder: UpdateEncoder, offset: Int) {
        encoder.writeJSON(jsonStringify(embed))
    }

    override fun getRef(): Int = 5

    companion object {
        fun read(decoder: UpdateDecoder): ContentEmbed {
            val str = decoder.readJSON()
            return ContentEmbed(jsonParse(str))
        }
    }
}
