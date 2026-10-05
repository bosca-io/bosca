package yks.structs.content

import yks.lib0.jsonParse
import yks.lib0.jsonStringify
import yks.structs.Item
import yks.utils.Transaction
import yks.utils.UpdateDecoder
import yks.utils.UpdateEncoder

/**
 * Legacy content holding JSON-serializable values.
 * Ref = 2. Length = array size. Countable.
 * Matches yjs ContentJSON.
 *
 * Note: This is a legacy content type. Modern yjs uses ContentAny (ref=8).
 * ContentJSON is kept for reading old yjs data.
 */
class ContentJSON(var values: MutableList<Any?>) : AbstractContent {
    override fun getLength(): Int = values.size
    override fun getContent(): List<Any?> = values
    override fun isCountable(): Boolean = true
    override fun copy(): AbstractContent = ContentJSON(values.toMutableList())

    override fun splice(offset: Int): AbstractContent {
        val right = ContentJSON(values.subList(offset, values.size).toMutableList())
        values = values.subList(0, offset).toMutableList()
        return right
    }

    override fun mergeWith(right: AbstractContent): Boolean = false

    override fun integrate(transaction: Transaction, item: Item) {}
    override fun delete(transaction: Transaction) {}
    override fun gc(transaction: Transaction) {}

    override fun write(encoder: UpdateEncoder, offset: Int) {
        val len = values.size - offset
        encoder.writeLen(len)
        for (i in offset until values.size) {
            val v = values[i]
            // Match yjs: JSON.stringify each value, write as string via writeJSON
            encoder.writeJSON(jsonStringify(v))
        }
    }

    override fun getRef(): Int = 2

    companion object {
        fun read(decoder: UpdateDecoder): ContentJSON {
            val len = decoder.readLen()
            val values = mutableListOf<Any?>()
            repeat(len) {
                val str = decoder.readJSON()
                // Match yjs: "undefined" maps to null; otherwise JSON.parse
                if (str == "undefined") {
                    values.add(null)
                } else {
                    values.add(jsonParse(str))
                }
            }
            return ContentJSON(values)
        }
    }
}
