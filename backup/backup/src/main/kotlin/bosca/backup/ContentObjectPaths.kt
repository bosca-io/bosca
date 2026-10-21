package bosca.backup

import bosca.content.model.ContentStorageReference
import bosca.content.model.ContentStorageReferenceKind
import bosca.serialization.UUID

/** Resolves the existing relative metadata path from its ID and logical version without storage I/O. */
internal fun metadataObjectPath(id: UUID, version: Int, supplementaryId: UUID? = null): String =
    if (supplementaryId == null) "metadata/$id/$version/content"
    else "metadata/$id/$version/supplementary/$supplementaryId"

/** Resolves the existing relative collection path from its ID without storage I/O. */
internal fun collectionObjectPath(id: UUID, supplementaryId: UUID? = null): String =
    if (supplementaryId == null) "collection/$id/content"
    else "collection/$id/supplementary/$supplementaryId"

/** Missing historical identities remain unresolved rather than borrowing current attachment IDs. */
internal fun contentObjectPath(reference: ContentStorageReference): String? = when (reference.kind) {
    ContentStorageReferenceKind.METADATA, ContentStorageReferenceKind.METADATA_VERSION ->
        if (reference.supplementaryId == null) {
            reference.version?.let { metadataObjectPath(reference.contentId, it) }
        } else null
    ContentStorageReferenceKind.METADATA_SUPPLEMENTARY,
    ContentStorageReferenceKind.METADATA_VERSION_SUPPLEMENTARY -> reference.version?.let { version ->
        reference.supplementaryId?.let { metadataObjectPath(reference.contentId, version, it) }
    }
    ContentStorageReferenceKind.COLLECTION_SUPPLEMENTARY ->
        reference.supplementaryId?.let { collectionObjectPath(reference.contentId, it) }
}
