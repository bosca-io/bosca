package bosca.backup

import bosca.serialization.UUID

/** Resolves the existing relative metadata path from its ID and logical version without storage I/O. */
internal fun metadataObjectPath(id: UUID, version: Int, supplementaryId: UUID? = null): String =
    if (supplementaryId == null) "metadata/$id/$version/content"
    else "metadata/$id/$version/supplementary/$supplementaryId"

/** Resolves the existing relative collection path from its ID without storage I/O. */
internal fun collectionObjectPath(id: UUID, supplementaryId: UUID? = null): String =
    if (supplementaryId == null) "collection/$id/content"
    else "collection/$id/supplementary/$supplementaryId"
