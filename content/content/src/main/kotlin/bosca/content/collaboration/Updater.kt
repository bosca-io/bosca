package bosca.content.collaboration

import yks.utils.Doc
import yks.utils.applyUpdate
import yks.utils.encodeStateAsUpdate

/**
 * Reads and writes dirty flags inside Yjs CRDT documents.
 *
 * Each flag lives as a `|__dirty__|` key inside a named [YMap]. The three maps
 * correspond to collections (`"collections"`), relationships (`"metadatas"`),
 * and attributes (`"attrs"`).
 */
class Updater : AutoCloseable {

    fun areCollectionsDirty(content: ByteArray) = isDirty(content, "collections")

    fun areRelationshipsDirty(content: ByteArray) = isDirty(content, "metadatas")

    fun areAttributesDirty(content: ByteArray) = isDirty(content, "attrs")

    fun setCollectionsDirty(content: ByteArray) = setDirty(content, "collections")

    fun setRelationshipsDirty(content: ByteArray) = setDirty(content, "metadatas")

    fun setAttributesDirty(content: ByteArray) = setDirty(content, "attrs")

    private fun isDirty(data: ByteArray, mapName: String): Boolean {
        if (data.isEmpty()) return false
        val doc = Doc()
        applyUpdate(doc, data)
        val map = doc.getMap(mapName)
        val data = map.get(DIRTY_KEY)?.toString()
        return data == "true"
    }

    private fun setDirty(data: ByteArray, mapName: String): ByteArray {
        val doc = Doc()
        if (data.isNotEmpty()) {
            applyUpdate(doc, data)
        }
        val map = doc.getMap(mapName)
        map.set(DIRTY_KEY, "true")
        return encodeStateAsUpdate(doc)
    }

    override fun close() {
    }

    private companion object {
        private const val DIRTY_KEY = "|__dirty__|"
    }
}