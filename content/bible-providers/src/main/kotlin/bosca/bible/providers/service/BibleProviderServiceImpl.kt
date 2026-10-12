package bosca.bible.providers.service

import bosca.bible.Bible as SharedBible
import bosca.bible.BibleAudio
import bosca.bible.BibleContentUse
import bosca.bible.BibleMetadata
import bosca.bible.Book
import bosca.bible.Chapter
import bosca.bible.IBible
import bosca.bible.bibleJson
import bosca.bible.providers.model.AddProviderAudioInput
import bosca.bible.providers.model.AddProviderBibleInput
import bosca.bible.providers.model.BibleCatalog
import bosca.bible.providers.model.BibleProviderConfiguration
import bosca.bible.providers.model.BibleProviderConfigurationInput
import bosca.bible.providers.model.BibleProviderSettings
import bosca.bible.providers.model.BibleSource
import bosca.bible.providers.model.BibleSourceKind
import bosca.bible.providers.model.IBibleProviderResponse
import bosca.configuration.model.Configuration
import bosca.configuration.model.ConfigurationInput
import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.toInput
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.db.connection
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.slug.service.SlugService
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

@ServiceImplementation
class BibleProviderServiceImpl(
    private val configurations: ConfigurationService,
    private val sources: BibleSourceService,
    private val metadata: MetadataService,
    private val bibles: BibleService,
    private val collections: CollectionService,
    private val slugs: SlugService,
    private val json: Json,
) : BibleProviderService {

    /** Lists the provider keys accepted when saving configurations. */
    @OptIn(InternalDI::class)
    override fun getProviders(): List<String> = ProviderRegistry.findAllWithNames(BibleProvider::class)
        .keys.filter { it.isNotEmpty() }.sorted()

    /** Lists only Bible provider configurations, including disabled configurations, without credentials. */
    override suspend fun getConfigurations(): List<BibleProviderConfiguration> = configurations.getAll()
        .filter { it.key.startsWith(KEY_PREFIX) }
        .map { details(it.id, settings(it)) }

    /** Saves encrypted settings; null credentials on an update retain the previously saved key. */
    override suspend fun saveConfiguration(input: BibleProviderConfigurationInput): BibleProviderConfiguration = transaction {
        require(input.name.isNotBlank()) { "A Bible provider configuration name is required" }
        require(input.provider in getProviders()) { "Unsupported Bible provider" }
        val existing = input.id?.let { find(it) }
        val previous = existing?.let { settings(it, forUpdate = true) }
        val apiKey = input.apiKey ?: previous?.apiKey
        require(!apiKey.isNullOrBlank()) { "A Bible provider API key is required" }
        val settings = BibleProviderSettings(input.name.trim(), input.provider, input.enabled, apiKey)
        val value = json.encodeToJsonElement(BibleProviderSettings.serializer(), settings)
        val id = if (existing == null) {
            configurations.setConfiguration(ConfigurationInput(
                key = "$KEY_PREFIX${UUID.random()}", description = "Bible provider configuration",
                value = value, public = false, permissions = emptyList(),
            )).id
        } else {
            // Replacing configuration metadata cascades to sources. Value updates preserve its identity and permissions.
            configurations.setValue(existing.id, value)
            if (previous?.apiKey != apiKey || previous.provider != input.provider || previous.enabled != input.enabled) {
                for (source in sources.getByConfiguration(existing.id)) {
                    sources.update(source.copy(provider = input.provider))
                    bibles.clearChapterContent(source.id)
                }
            }
            existing.id
        }
        details(id, settings)
    }

    /** Tests saved credentials even when disabled, without registering Bibles or fetching chapter bodies. */
    override suspend fun test(configurationId: UUID) {
        provider(settings(find(configurationId))).test(configurationId)
    }

    /** Resolves the registered provider for an enabled saved configuration. */
    override suspend fun getProvider(configurationId: UUID): BibleProvider {
        val settings = settings(find(configurationId))
        check(settings.enabled) { "Bible provider configuration is disabled" }
        return provider(settings)
    }

    /** Browses provider descriptions with offset pagination without creating Bosca content. */
    override suspend fun getCatalog(configurationId: UUID, kind: BibleSourceKind, query: String?, offset: Int, limit: Int): List<BibleCatalog> {
        require(offset >= 0 && limit >= 0) { "Catalog offset and limit must be nonnegative" }
        check(!connection().inTransaction) { "Provider catalog requests must run outside a database transaction" }
        return getProvider(configurationId).getCatalog(configurationId, kind, query, offset, limit).map { it.asSerializable() }
    }

    /** Lists active sources even when their configuration or Bible variant is disabled. */
    override suspend fun getSources(configurationId: UUID): List<BibleSource> {
        find(configurationId)
        return sources.getByConfiguration(configurationId)
    }

    /** Adds an unpublished text Bible and its reviewed uses; provider I/O precedes the content transaction. */
    override suspend fun addBible(input: AddProviderBibleInput): Bible {
        require(input.providerBibleId.isNotBlank()) { "A provider Bible identifier is required" }
        require(input.variant.isNotBlank()) { "A Bible variant is required" }
        require(BibleContentUse.PLAYBACK !in input.allowedUses) { "Text Bible sources cannot permit playback" }
        check(!connection().inTransaction) { "Bible registration must start outside a database transaction" }
        val provider = getProvider(input.configurationId)
        val parentId = slugs.get("bibles")?.collectionId ?: error("Bibles collection was not found")
        val parent = collections.getById(parentId) ?: error("Bibles collection was not found")
        val response = provider.getBible(input.configurationId, input.providerBibleId, BibleSourceKind.TEXT)
        val attribution = response.attribution?.asSerializable() ?: error("Provider Bible attribution is required")
        val remote = response.content
        val references = references(remote)
        val bibleInput = references.toInput(true, input.variant)
        val attributes = JsonObject(bibleJson.encodeToJsonElement(BibleMetadata.serializer(), references.metadata).jsonObject +
            ("type" to JsonPrimitive("Bible")))

        return transaction {
            val added = metadata.add(parent, null, MetadataInput(
                name = bibleInput.name, languageTag = references.metadata.language.iso, contentType = "bosca/v-bible",
                parentCollectionId = parentId, attributes = attributes,
            ))
            metadata.setBible(added, bibleInput)
            val source = sources.add(BibleSource(
                id = UUID.random(), configurationId = input.configurationId, provider = provider.key, providerBibleId = input.providerBibleId,
                metadataId = added.id, metadataVersion = added.version, variant = input.variant, kind = BibleSourceKind.TEXT,
                name = bibleInput.name, nameLocal = bibleInput.nameLocal, abbreviation = bibleInput.abbreviation,
                attribution = attribution, allowedUses = input.allowedUses,
            ))
            bibles.setChapterSource(added.id, added.version, input.variant, source.id)
            bibles.getBible(added.id, added.version, input.variant) ?: error("Registered Bible variant was not found")
        }
    }

    override suspend fun getFumsToken(bibleId: UUID, version: Int, variant: String, usfm: String, audioId: Uuid?): String? {
        // Text response tokens cannot identify an audio response; recordings have no cached responses yet.
        if (audioId != null) return null
        return bibles.getFumsToken(bibleId, version, variant, usfm)
    }

    override suspend fun getAudioTargets(configurationId: UUID, providerBibleId: String): List<Bible> {
        require(providerBibleId.isNotBlank()) { "A provider recording identifier is required" }
        check(!connection().inTransaction) { "Provider requests must run outside a database transaction" }
        val (catalog, response) = recording(getProvider(configurationId), configurationId, providerBibleId)
        val targets = mutableListOf<Bible>()
        for (identity in bibles.getBibles().distinctBy { it.metadataId to it.version }) {
            for (variant in bibles.getVariants(identity.metadataId, identity.version)) {
                if (compatible(variant, catalog, response.content)) targets.add(variant)
            }
        }
        if (targets.isEmpty()) return targets
        val liveMetadata = metadata.getByIds(targets.map { it.metadataId }.distinct())
            .filterNot { it.deleted }
            .map { it.id }.toSet()
        return targets.filter { it.metadataId in liveMetadata }
    }

    override suspend fun addAudio(bible: Bible, input: AddProviderAudioInput): BibleAudio {
        require(input.providerBibleId.isNotBlank()) { "A provider recording identifier is required" }
        require(input.allowedUses.all { it == BibleContentUse.PLAYBACK }) { "Audio sources may permit only playback" }
        check(!connection().inTransaction) { "Audio registration must start outside a database transaction" }
        val target = variant(bible.metadataId, bible.version, bible.variant)
        val content = metadata.getById(target.metadataId) ?: error("Bible metadata was not found")
        check(!content.deleted) { "Bible metadata was deleted" }
        val provider = getProvider(input.configurationId)
        val (catalog, response) = recording(provider, input.configurationId, input.providerBibleId)
        require(compatible(target, catalog, response.content)) { "Recording is not compatible with the Bible variant" }
        val attribution = response.attribution?.asSerializable() ?: error("Provider recording attribution is required")
        val description = response.content.metadata.identification
        val source = sources.add(BibleSource(
            id = UUID.random(), configurationId = input.configurationId, provider = provider.key, providerBibleId = input.providerBibleId,
            metadataId = target.metadataId, metadataVersion = target.version, variant = target.variant, kind = BibleSourceKind.AUDIO,
            name = description.name, nameLocal = description.nameLocal, abbreviation = description.abbreviation,
            attribution = attribution, allowedUses = input.allowedUses,
        ))
        return BibleAudio(source.id, source.name, source.nameLocal, source.abbreviation, source.attribution)
    }

    override suspend fun refresh(sourceId: UUID) {
        check(!connection().inTransaction) { "Provider refresh must start outside a database transaction" }
        val source = sources.getById(sourceId) ?: error("Bible source was not found")
        val provider = getProvider(source.configurationId)
        val target = variant(source.metadataId, source.metadataVersion, source.variant)
        val response = if (source.kind == BibleSourceKind.AUDIO) {
            val (catalog, recording) = recording(provider, source.configurationId, source.providerBibleId)
            require(compatible(target, catalog, recording.content)) { "Recording is not compatible with the Bible variant" }
            recording
        } else {
            provider.getBible(source.configurationId, source.providerBibleId, source.kind)
        }
        val attribution = response.attribution?.asSerializable() ?: error("Provider source attribution is required")
        val input = references(response.content).toInput(target.defaultVariant, source.variant)
        transaction {
            sources.update(source.copy(name = input.name, nameLocal = input.nameLocal,
                abbreviation = input.abbreviation, attribution = attribution))
            bibles.clearChapterContent(source.id)
            if (source.kind == BibleSourceKind.TEXT) {
                val content = metadata.getById(source.metadataId, source.metadataVersion) ?: error("Bible metadata was not found")
                metadata.setBible(content, input)
                bibles.setChapterSource(source.metadataId, source.metadataVersion, source.variant, source.id)
            }
        }
    }

    override suspend fun removeSource(sourceId: UUID, expectedVersion: Long) = transaction {
        sources.remove(sourceId, expectedVersion)
        bibles.clearChapterContent(sourceId)
        Unit
    }

    override suspend fun removeConfiguration(configurationId: UUID, purgeSources: Boolean) = transaction {
        val configuration = find(configurationId)
        configurations.getValueForUpdate(configurationId)
        val attached = sources.getByConfiguration(configurationId)
        check(purgeSources || attached.isEmpty()) { "Bible provider configuration has active sources" }
        for (source in attached) {
            sources.remove(source.id, source.version)
            bibles.clearChapterContent(source.id)
        }
        configurations.deleteConfiguration(configuration.key)
        Unit
    }

    private suspend fun variant(metadataId: UUID, version: Int, name: String): Bible =
        bibles.getVariants(metadataId, version).singleOrNull { it.variant == name }
            ?: error("Bible variant was not found")

    private suspend fun recording(provider: BibleProvider, configurationId: UUID, id: String): Pair<BibleCatalog, IBibleProviderResponse<IBible>> {
        val catalog = provider.getCatalog(configurationId, BibleSourceKind.AUDIO, null, 0, Int.MAX_VALUE)
            .singleOrNull { it.id == id }?.asSerializable() ?: error("Provider recording was not found")
        return catalog to provider.getBible(configurationId, id, BibleSourceKind.AUDIO)
    }

    private suspend fun compatible(bible: Bible, catalog: BibleCatalog, recording: IBible): Boolean {
        val systemId = catalog.relatedSystemId ?: catalog.systemId
        if (systemId.isBlank() || bible.systemId != systemId) return false
        if (bibles.getLanguages(bible).none { it.iso == recording.metadata.language.iso }) return false
        val references = recording.books.flatMap { it.chapters }.map { it.reference.chapterUsfm }.toSet()
        if (references.isEmpty()) return false
        val available = mutableSetOf<String>()
        for (book in bibles.getBooks(bible)) available.addAll(bibles.getChapters(book).map { it.usfm })
        return available.containsAll(references)
    }

    private fun references(bible: IBible): SharedBible = SharedBible(bible.metadata.asSerializable(), bible.books.map { book ->
        Book(book.reference, book.name.asSerializable(), book.chapters.map { Chapter(it.reference, null) })
    }, bible.styles)

    private suspend fun find(id: UUID): Configuration = configurations.getAll()
        .singleOrNull { it.id == id && it.key.startsWith(KEY_PREFIX) }
        ?: throw IllegalArgumentException("Bible provider configuration was not found")

    private suspend fun settings(configuration: Configuration, forUpdate: Boolean = false): BibleProviderSettings {
        return try {
            requireNotNull(configurations.getValueAs<BibleProviderSettings>(configuration.id, json, forUpdate)) {
                "Bible provider configuration has no settings"
            }
        } catch (_: SerializationException) {
            throw SerializationException("Invalid Bible provider settings for configuration ${configuration.id}")
        }
    }

    private suspend fun provider(settings: BibleProviderSettings): BibleProvider {
        require(settings.provider in getProviders()) { "Unsupported Bible provider" }
        return provide<BibleProvider>(settings.provider)
    }

    private fun details(id: UUID, settings: BibleProviderSettings) = BibleProviderConfiguration(
        id, settings.name, settings.provider, settings.enabled, !settings.apiKey.isNullOrBlank(),
    )

    private companion object {
        const val KEY_PREFIX = "bible-provider:"
    }
}
