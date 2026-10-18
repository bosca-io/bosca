package bosca.storage.service

import kotlinx.serialization.Serializable

/** One literal object key and its observed listing metadata, without a retained revision guarantee. */
@Serializable
data class ObjectStorageEntry(val key: String, val info: ObjectStorageInfo) {
    init {
        require(key.isNotEmpty()) { "Object key must not be empty" }
    }
}
