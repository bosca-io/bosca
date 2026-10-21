package bosca.content.model

import kotlinx.serialization.Serializable

/** The database record describing a possible object-storage asset. */
@Serializable
enum class ContentStorageReferenceKind {
    METADATA,
    METADATA_SUPPLEMENTARY,
    METADATA_VERSION,
    METADATA_VERSION_SUPPLEMENTARY,
    COLLECTION_SUPPLEMENTARY,
}
