package bosca.bible.providers.service

import bosca.bible.BibleContentUse
import bosca.bible.BibleAudioContent
import bosca.bible.Chapter
import bosca.bible.providers.model.BibleProviderResponse
import bosca.bible.providers.model.BibleSource
import bosca.bible.providers.model.BibleSourceKind
import bosca.bible.providers.configuration.getBibleProviderSettings
import bosca.bible.providers.repository.BibleSourceRepository
import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.configuration.service.ConfigurationService
import bosca.di.provide
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.Json
import java.io.OutputStream

@ServiceImplementation
class BibleSourceServiceImpl(
    private val sources: BibleSourceRepository,
    private val configurations: ConfigurationService,
    private val json: Json,
) : BibleSourceService {

    private val sourceCache = ServiceCache("bible:source", UUIDKeySerializer) { sources.get(it) }
    private val validationCache = ServiceCache("bible:source:validation", UUIDKeySerializer) { id ->
        val source = getById(id) ?: return@ServiceCache null
        val settings = configurations.getBibleProviderSettings(source.configurationId, json)
        check(settings.enabled) { "Bible provider configuration is disabled" }
        check(settings.provider == source.provider) { "Bible source belongs to another provider" }
        source.version
    }

    override suspend fun add(source: BibleSource): BibleSource {
        require(when (source.kind) {
            BibleSourceKind.TEXT -> BibleContentUse.PLAYBACK !in source.allowedUses
            BibleSourceKind.AUDIO -> source.allowedUses.all { it == BibleContentUse.PLAYBACK }
        }) { "Reviewed uses do not match the Bible source kind" }
        return (sources.add(source) ?: error("Bible metadata was deleted")).also {
            sourceCache.put(it.id, it)
            validationCache.remove(it.id)
        }
    }

    override suspend fun getById(id: UUID): BibleSource? = sourceCache.get(id)

    override suspend fun getForUse(id: UUID, use: BibleContentUse): BibleSource {
        val source = getById(id) ?: error("Bible source was removed")
        require(use in source.allowedUses) { "Bible source does not permit the requested use" }
        // Validation applies only to the source version checked against its configuration.
        if (validationCache.get(id) != source.version) {
            validationCache.remove(id)
            check(validationCache.get(id) == source.version) { "Bible source changed during the request" }
        }
        return source
    }

    override suspend fun getText(metadataId: UUID, metadataVersion: Int, variant: String): BibleSource? =
        sources.getText(metadataId, metadataVersion, variant)

    override suspend fun getAudio(metadataId: UUID, metadataVersion: Int, variant: String): List<BibleSource> =
        sources.getAudio(metadataId, metadataVersion, variant)

    override suspend fun getByConfiguration(configurationId: UUID): List<BibleSource> = sources.getByConfiguration(configurationId)

    override suspend fun getChapter(source: BibleSource, usfm: String): BibleProviderResponse<Chapter> =
        provide<BibleProvider>(source.provider).getChapter(source.configurationId, source.providerBibleId, usfm)
            .asSerializable { it.asSerializable() }

    override suspend fun downloadAudioChapter(source: BibleSource, usfm: String, destination: OutputStream): BibleProviderResponse<BibleAudioContent> =
        provide<BibleProvider>(source.provider).downloadAudioChapter(source.configurationId, source.providerBibleId, usfm, destination)
            .asSerializable { it.asSerializable() }

    override suspend fun update(source: BibleSource): BibleSource =
        (sources.update(source) ?: error("Bible source was removed or its version changed"))
            .also {
                sourceCache.remove(it.id)
                validationCache.remove(it.id)
            }

    override suspend fun deleteByBible(metadataId: UUID, metadataVersion: Int): List<BibleSource> =
        sources.deleteByBible(metadataId, metadataVersion).onEach {
            sourceCache.remove(it.id)
            validationCache.remove(it.id)
        }

    override suspend fun remove(id: UUID, expectedVersion: Long): BibleSource =
        (sources.remove(id, expectedVersion) ?: error("Bible source was removed or its version changed"))
            .also {
                sourceCache.remove(it.id)
                validationCache.remove(it.id)
            }
}
