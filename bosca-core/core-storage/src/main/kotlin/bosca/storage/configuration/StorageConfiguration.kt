package bosca.storage.configuration

import kotlinx.serialization.Serializable

/** Configuration of one filesystem, S3-compatible or no-op object storage endpoint. */
@Serializable
data class StorageConfiguration(
    val type: String,
    val urlPrefix: String,
    val urlUploadPrefix: String? = null,
    val bucket: String? = null,
    val createBucket: Boolean = false,
    val basePath: String? = null,
    val forcePathStyle: Boolean? = null,
    val region: String? = null,
    val endpoint: String? = null,
    val accessKeyId: String? = null,
    val accessKeySecret: String? = null,
)
