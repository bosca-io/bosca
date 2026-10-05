package yks.structs.content

import yks.structs.Item
import yks.utils.Doc
import yks.utils.Transaction
import yks.utils.UpdateDecoder
import yks.utils.UpdateEncoder

/**
 * Content wrapping a subdocument.
 * Ref = 9. Length = 1. Countable.
 * Matches yjs ContentDoc.
 */
class ContentDoc(var doc: Doc) : AbstractContent {
    override fun getLength(): Int = 1
    override fun getContent(): List<Any?> = listOf(doc)
    override fun isCountable(): Boolean = true
    override fun copy(): AbstractContent = ContentDoc(doc)

    override fun splice(offset: Int): AbstractContent {
        throw UnsupportedOperationException("ContentDoc cannot be split")
    }

    override fun mergeWith(right: AbstractContent): Boolean = false

    override fun integrate(transaction: Transaction, item: Item) {
        doc.item = item
        transaction.subdocsAdded.add(doc)
        if (doc.shouldLoad) {
            transaction.subdocsLoaded.add(doc)
        }
    }

    override fun delete(transaction: Transaction) {
        transaction.subdocsRemoved.add(doc)
    }

    override fun gc(transaction: Transaction) {}

    override fun write(encoder: UpdateEncoder, offset: Int) {
        encoder.writeString(doc.guid)
        encoder.writeAny(doc.opts)
    }

    override fun getRef(): Int = 9

    companion object {
        fun read(decoder: UpdateDecoder): ContentDoc {
            val guid = decoder.readString()
            val opts = decoder.readAny()
            return ContentDoc(Doc(guid = guid, opts = opts))
        }
    }
}
