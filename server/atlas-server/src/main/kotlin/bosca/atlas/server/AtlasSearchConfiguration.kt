package bosca.atlas.server

import bosca.category.service.CategoryService
import bosca.configuration.DocumentToTextProvider
import bosca.configuration.MetadataDocumentToTextProvider
import bosca.configuration.service.ConfigurationService
import bosca.content.collection.jobs.CollectionIndexExecutor
import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.jobs.MetadataIndexExecutor
import bosca.content.metadata.model.LocaleAwareDocument
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.DataService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.MetadataService
import bosca.content.transformations.CollectionToSearchDocument
import bosca.content.transformations.CollectionToSearchDocumentConfiguration
import bosca.content.transformations.DefaultMetadataToSearchCollections
import bosca.content.transformations.DocumentReferencesToListTransformation
import bosca.content.transformations.IndexableDocumentToTextTransformation
import bosca.content.transformations.MetadataDocumentToTextTransformation
import bosca.content.transformations.MetadataToSearchDocument
import bosca.content.transformations.MetadataToSearchDocumentConfiguration
import bosca.content.transformations.ProfileToSearchDocument
import bosca.content.transformations.ProfileToSearchDocumentConfiguration
import bosca.content.transformations.ReferencesListToBookListTransformation
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.profile.model.Profile
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.jobs.ProfileIndexExecutor
import bosca.profile.profile.service.ProfileService
import bosca.search.IndexStorageSystem
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import bosca.transformations.Transformation
import bosca.server.BoscaApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Composes the existing content transformations used by indexing jobs and System previews. */
@Providers
class AtlasSearchConfiguration {
    @Provider(name = MetadataDocumentToTextProvider)
    fun metadataDocumentToText(
        metadataService: MetadataService,
        documentService: DocumentService,
        bibleService: BibleService,
        json: Json
    ): Transformation<IndexStorageSystem, Metadata, String> = MetadataDocumentToTextTransformation(metadataService, documentService, bibleService, json)

    @Provider(name = DocumentToTextProvider)
    fun indexableDocumentToText(
        metadataService: MetadataService,
        bibleService: BibleService,
        json: Json
    ): Transformation<IndexStorageSystem, LocaleAwareDocument, String> = IndexableDocumentToTextTransformation(metadataService, bibleService, json)

    @Provider(singleton = true)
    fun metadataToSearchDocumentConfiguration(
        collectionsService: CollectionService,
        documentService: DocumentService,
        dataService: DataService,
        guideService: GuideService,
        application: BoscaApplication
    ) = MetadataToSearchDocumentConfiguration(
        DefaultMetadataToSearchCollections(collectionsService),
        documentService,
        guideService,
        dataService,
        excludeTypes = application.environment.config.propertyOrNull("content.index.excludeTypes")?.getList()?.toSet() ?: emptySet(),
        excludeContentTypePrefix = application.environment.config.propertyOrNull("content.index.excludeContentTypePrefix")?.getList() ?: emptyList()
    )

    @Provider(singleton = true)
    fun collectionToSearchDocumentConfiguration(application: BoscaApplication) = CollectionToSearchDocumentConfiguration(
        excludeTypes = application.environment.config.propertyOrNull("content.index.excludeTypes")?.getList()?.toSet() ?: emptySet(),
        excludeContentTypePrefix = application.environment.config.propertyOrNull("content.index.excludeContentTypePrefix")?.getList() ?: emptyList()
    )

    @Provider(singleton = true)
    fun profileToSearchDocumentConfiguration(application: BoscaApplication) = ProfileToSearchDocumentConfiguration(
        excludeTypes = application.environment.config.propertyOrNull("content.index.excludeTypes")?.getList()?.toSet() ?: emptySet(),
        excludeContentTypePrefix = application.environment.config.propertyOrNull("content.index.excludeContentTypePrefix")?.getList() ?: emptyList()
    )

    @Provider(singleton = true)
    fun documentReferencesToListTransformation(
        metadataService: MetadataService,
        documentService: DocumentService,
        json: Json
    ) = DocumentReferencesToListTransformation(metadataService, documentService, json)

    @Provider(singleton = true)
    fun referencesListToBookListTransformation(
        bibleService: BibleService,
        metadataService: MetadataService
    ) = ReferencesListToBookListTransformation(bibleService, metadataService)

    @Provider(singleton = true)
    fun metadataToSearchDocument(
        metadataService: MetadataService,
        @ProviderName(MetadataDocumentToTextProvider)
        documentToText: Transformation<IndexStorageSystem, Metadata, String>,
        storage: ObjectStorageService,
        slugs: SlugService,
        configuration: MetadataToSearchDocumentConfiguration,
        documentReferences: DocumentReferencesToListTransformation,
        referencesToBookList: ReferencesListToBookListTransformation,
        configurationService: ConfigurationService,
        json: Json
    ): MetadataToSearchDocument = MetadataToSearchDocument(
        metadataService,
        documentToText,
        storage,
        slugs,
        json,
        configuration,
        documentReferences,
        referencesToBookList,
        configurationService
    )

    @Provider(name = MetadataIndexExecutor.TransformProvider)
    fun metadataToSearch(
        metadataToSearchDocument: MetadataToSearchDocument
    ): Transformation<IndexStorageSystem, Metadata, JsonElement?> = metadataToSearchDocument

    @Provider(singleton = true)
    fun collectionToSearchDocument(
        collectionService: CollectionService,
        slugs: SlugService,
        configuration: CollectionToSearchDocumentConfiguration,
        categoryService: CategoryService,
        configurationService: ConfigurationService,
        json: Json
    ): CollectionToSearchDocument = CollectionToSearchDocument(
        collectionService,
        slugs,
        json,
        configuration,
        categoryService,
        configurationService
    )

    @Provider(name = CollectionIndexExecutor.TransformProvider)
    fun collectionToSearch(
        collectionToSearchDocument: CollectionToSearchDocument
    ): Transformation<IndexStorageSystem, Collection, List<JsonElement>> = collectionToSearchDocument

    @Provider(singleton = true)
    fun profileToSearchDocument(
        profileService: ProfileService,
        organizationService: OrganizationService,
        slugs: SlugService,
        configuration: ProfileToSearchDocumentConfiguration,
        configurationService: ConfigurationService,
        json: Json
    ): ProfileToSearchDocument = ProfileToSearchDocument(
        profileService,
        organizationService,
        slugs,
        json,
        configuration,
        configurationService
    )

    @Provider(name = ProfileIndexExecutor.TransformProvider)
    fun profileToSearch(
        profileToSearchDocument: ProfileToSearchDocument
    ): Transformation<IndexStorageSystem, Profile, JsonElement?> = profileToSearchDocument

}
