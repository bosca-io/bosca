package bosca.bible.providers.service

import bosca.bible.BibleChapterContent
import bosca.bible.BibleContentUse
import bosca.bible.providers.model.BibleChapterCache
import bosca.bible.providers.model.BibleProviderSettings
import bosca.bible.providers.model.BibleSource
import bosca.bible.providers.model.BibleSourceKind
import bosca.bible.providers.repository.BibleChapterCacheRepository
import bosca.bible.providers.repository.BibleSourceRepository
import bosca.configuration.service.ConfigurationService
import bosca.db.connection
import bosca.db.transaction
import bosca.di.provide
import bosca.lock.DistributedLockFactory
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@ServiceImplementation
class BibleSourceServiceImpl(
    private val sources: BibleSourceRepository,
    private val chapters: BibleChapterCacheRepository,
    private val configurations: ConfigurationService,
    private val locks: DistributedLockFactory,
    private val json: Json,
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

    override suspend fun getChapterContent(
        metadataId: UUID, metadataVersion: Int, variant: String, usfm: String, use: BibleContentUse,
    ): BibleChapterContent? {
        val source = sources.findText(metadataId, metadataVersion, variant) ?: return null
        val settings = transaction { available(source, use) }
        cached(source, usfm, use)?.let { return it }
        check(!connection().inTransaction) { "Provider chapter requests must run outside a database transaction" }
        val lock = locks.create("bible:chapter:${settings.provider}:${source.providerBibleId}")
        return lock.withLock(
            ttlMillis = 60_000, waitTimeoutMillis = 30_000,
        ) {
            cached(source, usfm, use)?.let { return@withLock it }
            val created = OffsetDateTime.now()
            val provider = provide<BibleProvider>(settings.provider)
            val response = provider.getChapter(source.configurationId, source.providerBibleId, usfm).asSerializable { it.asSerializable() }
            check(response.content.reference.usfm == usfm) { "Provider chapter does not match the requested reference" }
            val verseCount = checkNotNull(response.verseCount) { "Provider chapter verse count is required" }
            check(verseCount >= 0) { "Provider chapter verse count is invalid" }
            check(settings.provider != "API_BIBLE" || verseCount < 500) { "Provider chapter exceeds the text cache limit" }
            val chapter = BibleChapterCache(
                source.id, settings.provider, usfm, response.content, response.attribution,
                verseCount, response.fumsToken, created, created.plusDays(14),
            )
            check(lock.renew(60_000)) { "Provider chapter fetch lock expired" }
            transaction {
                available(source, use)
                val current = unchanged(source)
                removeExpired()
                chapters.put(chapter)
                if (settings.provider == "API_BIBLE") limit(chapter, source.providerBibleId)
                BibleChapterContent(chapter.content, chapter.attribution ?: current.attribution)
            }
        } ?: error("Timed out waiting for the provider chapter")
    }

    override suspend fun removeExpired(): Int = chapters.removeExpired(OffsetDateTime.now())

    private suspend fun cached(source: BibleSource, usfm: String, use: BibleContentUse): BibleChapterContent? = transaction {
        available(source, use)
        val current = unchanged(source)
        chapters.get(source.id, usfm, OffsetDateTime.now())?.let {
            BibleChapterContent(it.content, it.attribution ?: current.attribution)
        }
    }

    private suspend fun unchanged(source: BibleSource): BibleSource {
        val current = sources.lock(source.id) ?: error("Bible source was removed")
        check(current.version == source.version) { "Bible source changed during the request" }
        return current
    }

    private suspend fun available(source: BibleSource, use: BibleContentUse): BibleProviderSettings {
        check(source.deletedAt == null) { "Bible source was removed" }
        require(use in source.allowedUses) { "Bible source does not permit the requested use" }
        val value = configurations.getValueForUpdate(source.configurationId) ?: error("Bible provider configuration has no settings")
        val settings = try {
            json.decodeFromJsonElement(BibleProviderSettings.serializer(), value)
        } catch (_: SerializationException) {
            throw SerializationException("Invalid Bible provider settings for configuration ${source.configurationId}")
        }
        check(settings.enabled) { "Bible provider configuration is disabled" }
        return settings
    }

    /** Caps total retained verses per upstream edition, including copies registered with other credentials. */
    private suspend fun limit(keep: BibleChapterCache, providerBibleId: String) {
        var remaining = 499 - keep.verseCount
        for (chapter in chapters.getByEdition(keep.provider, providerBibleId, OffsetDateTime.now())) {
            if (chapter.sourceId == keep.sourceId && chapter.usfm == keep.usfm) continue
            if (chapter.verseCount <= remaining) remaining -= chapter.verseCount
            else chapters.remove(chapter.sourceId, chapter.usfm)
        }
    }

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
