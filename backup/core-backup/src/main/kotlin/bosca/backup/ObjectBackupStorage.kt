package bosca.backup

import bosca.db.annotation.ColumnName
import java.time.OffsetDateTime

/** Immutable configured object-store address; credentials and URL presentation are not stored. */
data class ObjectBackupStorage(
    val key: String,
    val type: String,
    val endpoint: String? = null,
    val bucket: String? = null,
    val region: String? = null,
    @ColumnName("base_path")
    val basePath: String? = null,
    val created: OffsetDateTime = OffsetDateTime.now(),
)
