package bosca.bible.providers.model

import bosca.bible.BibleAttribution
import bosca.bible.BibleContentUse
import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Connects one provider text Bible or recording to an exact Bosca Bible variant. A variant
 * has at most one text source and may have multiple audio sources. Local DBL content needs
 * no provider source. Availability follows the configuration and Bible variant; [deletedAt]
 * records explicit removal of this source.
 */
@Serializable
data class BibleSource(
    @Contextual val id: UUID,
    @Contextual @ColumnName("configuration_id") val configurationId: UUID,
    val provider: String,
    @ColumnName("provider_bible_id") val providerBibleId: String,
    @Contextual @ColumnName("metadata_id") val metadataId: UUID,
    @ColumnName("metadata_version") val metadataVersion: Int,
    val variant: String,
    val kind: BibleSourceKind,
    val name: String,
    @ColumnName("name_local") val nameLocal: String,
    val abbreviation: String,
    @property:DbMapper(JsonbMapper::class) val attribution: BibleAttribution,
    @ColumnName("allowed_uses")
    @property:DbMapper(JsonbMapper::class)
    val allowedUses: Set<BibleContentUse>,
    val version: Long = 0,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual @ColumnName("deleted_at") val deletedAt: OffsetDateTime? = null,
)
