package bosca.bible.providers.service

import bosca.bible.BibleContentUse
import bosca.bible.providers.model.BibleSource
import bosca.bible.providers.model.BibleSourceKind
import bosca.bible.providers.repository.BibleChapterCacheRepository
import bosca.bible.providers.repository.BibleSourceRepository
import bosca.db.transaction
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class BibleSourceServiceImpl(
    private val sources: BibleSourceRepository,
    private val chapters: BibleChapterCacheRepository,
) : BibleSourceService {

    override suspend fun add(source: BibleSource): BibleSource {
        require(when (source.kind) {
            BibleSourceKind.TEXT -> BibleContentUse.PLAYBACK !in source.allowedUses
            BibleSourceKind.AUDIO -> source.allowedUses.all { it == BibleContentUse.PLAYBACK }
        }) { "Reviewed uses do not match the Bible source kind" }
        return sources.add(source)
    }

    override suspend fun getById(id: UUID): BibleSource? = sources.get(id)

    override suspend fun getText(metadataId: UUID, metadataVersion: Int, variant: String): BibleSource? =
        sources.getText(metadataId, metadataVersion, variant)

    override suspend fun getAudio(metadataId: UUID, metadataVersion: Int, variant: String): List<BibleSource> =
        sources.getAudio(metadataId, metadataVersion, variant)

    override suspend fun getByConfiguration(configurationId: UUID): List<BibleSource> = sources.getByConfiguration(configurationId)

    override suspend fun update(source: BibleSource): BibleSource = transaction {
        val updated = sources.update(source) ?: error("Bible source was removed or its version changed")
        chapters.removeBySource(source.id)
        updated
    }

    override suspend fun remove(id: UUID, expectedVersion: Long): BibleSource = transaction {
        val removed = sources.remove(id, expectedVersion) ?: error("Bible source was removed or its version changed")
        chapters.removeBySource(id)
        removed
    }

    override suspend fun getFumsToken(metadataId: UUID, metadataVersion: Int, variant: String, usfm: String): String? =
        chapters.getFumsToken(metadataId, metadataVersion, variant, usfm, OffsetDateTime.now())
}
