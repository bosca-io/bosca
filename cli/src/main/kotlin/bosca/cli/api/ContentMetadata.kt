package bosca.cli.api

import bosca.graphql.client.GraphQLUpload
import bosca.graphql.client.Upload
import bosca.graphql.client.execute
import bosca.graphql.client.executeUpload
import bosca.graphql.gen.*
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

class ContentMetadata(network: NetworkClient) : Api(network) {

    suspend fun getAll(offset: Int, limit: Int): List<GetAllMetadataData.Content.FindMetadata> =
        network.boscaGraphql.execute(GetAllMetadata, GetAllMetadata.Variables(offset, limit)).content.findMetadata

    suspend fun get(id: Uuid): GetMetadataData.Content.Metadata? =
        network.boscaGraphql.execute(GetMetadata, GetMetadata.Variables(id)).content.metadata

    suspend fun getBySlug(slug: String): GetSlugData.Content.Slug.Metadata? =
        network.boscaGraphql.execute(GetSlug, GetSlug.Variables(slug)).content.slug as? GetSlugData.Content.Slug.Metadata

    suspend fun getBibleChapterContent(id: Uuid, version: Int?, usfm: String): GetBibleMetadataChapterData.Content.Metadata.Bible.Chapter? =
        network.boscaGraphql.execute(GetBibleMetadataChapter, GetBibleMetadataChapter.Variables(id, version, usfm)).content.metadata?.bible?.chapter

    suspend fun getBibleHumanReference(id: Uuid, version: Int?, usfm: String): String? =
        network.boscaGraphql.execute(GetBibleReference, GetBibleReference.Variables(id, version, usfm)).content.metadata?.bible?.chapter?.reference?.human

    suspend fun getDocument(id: Uuid, version: Int): GetMetadataDocumentData.Content.Metadata.Document? =
        network.boscaGraphql.execute(GetMetadataDocument, GetMetadataDocument.Variables(id, version)).content.metadata?.document

    suspend fun getDocumentTemplate(id: Uuid, version: Int): GetMetadataDocumentTemplateData.Content.Metadata.DocumentTemplate? =
        network.boscaGraphql.execute(GetMetadataDocumentTemplate, GetMetadataDocumentTemplate.Variables(id, version)).content.metadata?.documentTemplate

    suspend fun getDocumentTemplates(): List<GetMetadataDocumentTemplatesData.Content.DocumentTemplates.All> =
        network.boscaGraphql.execute(GetMetadataDocumentTemplates, Unit).content.documentTemplates.all

    suspend fun getGuide(id: Uuid, version: Int): GetMetadataGuideData.Content.Metadata.Guide? =
        network.boscaGraphql.execute(GetMetadataGuide, GetMetadataGuide.Variables(id, version)).content.metadata?.guide

    suspend fun getGuideTemplate(id: Uuid, version: Int): GetMetadataGuideTemplateData.Content.Metadata.GuideTemplate? =
        network.boscaGraphql.execute(GetMetadataGuideTemplate, GetMetadataGuideTemplate.Variables(id, version)).content.metadata?.guideTemplate

    suspend fun addGuideTemplateAttribute(id: Uuid, version: Int, attribute: TemplateAttributeInput, sort: Int = 0) {
        network.boscaGraphql.execute(AddGuideTemplateAttribute, AddGuideTemplateAttribute.Variables(id, version, attribute, sort))
    }

    suspend fun setGuideTemplateDefaultAttributes(id: Uuid, version: Int, attributes: JsonElement) {
        network.boscaGraphql.execute(SetGuideTemplateDefaultAttributes, SetGuideTemplateDefaultAttributes.Variables(id, version, attributes))
    }

    suspend fun getCategories(id: Uuid, version: Int): List<ICategory>? =
        network.boscaGraphql.execute(GetMetadataCategories, GetMetadataCategories.Variables(id, version)).content.metadata?.categories

    suspend fun getSource(id: Uuid): GetSourceData.Content.Sources.Source? =
        network.boscaGraphql.execute(GetSource, GetSource.Variables(id)).content.sources.source

    suspend fun getParents(id: Uuid): List<GetMetadataParentsData.Content.Metadata.ParentCollections> =
        network.boscaGraphql.execute(GetMetadataParents, GetMetadataParents.Variables(id)).content.metadata?.parentCollections ?: emptyList()

    suspend fun findMetadata(
        attributes: List<FindAttributeInput> = emptyList(),
        contentTypes: List<String>? = null,
        traitIds: List<String>? = null,
        categoryIds: List<String>? = null,
        offset: Int = 0,
        limit: Int = 10,
        extensions: ExtensionFilterType? = null,
    ): List<FindMetadataData.Content.FindMetadata> =
        network.boscaGraphql.execute(
            FindMetadata,
            FindMetadata.Variables(
                FindQueryInput(
                    attributes = listOf(FindAttributesInput(attributes)),
                    categoryIds = categoryIds,
                    traitIds = traitIds,
                    contentTypes = contentTypes,
                    extensionFilter = extensions,
                    offset = offset,
                    limit = limit,
                ),
            ),
        ).content.findMetadata

    suspend fun findMetadataBySystem(
        attributes: List<FindAttributeInput> = emptyList(),
        contentTypes: List<String>? = null,
        categoryIds: List<String>? = null,
        offset: Int = 0,
        limit: Int = 10,
        extensions: ExtensionFilterType? = null,
    ): List<FindMetadataBySystemData.Content.FindMetadataBySystem> =
        network.boscaGraphql.execute(
            FindMetadataBySystem,
            FindMetadataBySystem.Variables(
                FindQueryInput(
                    attributes = listOf(FindAttributesInput(attributes)),
                    categoryIds = categoryIds,
                    contentTypes = contentTypes,
                    extensionFilter = extensions,
                    offset = offset,
                    limit = limit,
                ),
            ),
        ).content.findMetadataBySystem

    suspend fun getCollectionTemplate(id: Uuid): GetCollectionTemplateData.Content.Metadata.CollectionTemplate =
        network.boscaGraphql.execute(GetCollectionTemplate, GetCollectionTemplate.Variables(id)).content.metadata?.collectionTemplate
            ?: error("No collection template returned")

    suspend fun getPermissions(id: Uuid): List<GetMetadataPermissionsData.Content.Metadata.Permissions> =
        network.boscaGraphql.execute(GetMetadataPermissions, GetMetadataPermissions.Variables(id)).content.metadata?.permissions ?: emptyList()

    suspend fun getTextContents(id: Uuid): String? =
        network.boscaGraphql.execute(GetTextContents, GetTextContents.Variables(id)).content.metadata?.content?.text

    suspend fun getSupplementaryContentDownload(supplementaryId: Uuid): GetMetadataSupplementaryDownloadData.Content.MetadataSupplementary.Content.Urls.Download? =
        network.boscaGraphql.execute(GetMetadataSupplementaryDownload, GetMetadataSupplementaryDownload.Variables(supplementaryId)).content.metadataSupplementary?.content?.urls?.download

    suspend fun getMetadataContentDownload(id: Uuid): GetMetadataDownloadData.Content.Metadata.Content.Urls.Download? =
        network.boscaGraphql.execute(GetMetadataDownload, GetMetadataDownload.Variables(id)).content.metadata?.content?.urls?.download

    suspend fun getMetadataContentUpload(id: Uuid): GetMetadataUploadData.Content.Metadata.Content.Urls.Upload? =
        network.boscaGraphql.execute(GetMetadataUpload, GetMetadataUpload.Variables(id)).content.metadata?.content?.urls?.upload

    suspend fun getSupplementaryTextContents(id: Uuid, key: String): String? =
        network.boscaGraphql.execute(GetSupplementaryTextContents, GetSupplementaryTextContents.Variables(id, key)).content.metadata?.supplementary?.firstOrNull()?.content?.text

    suspend fun add(metadata: MetadataInput): Uuid? =
        network.boscaGraphql.execute(AddMetadata, AddMetadata.Variables(metadata)).content.metadata.add.id

    suspend fun addDocument(parentCollectionId: Uuid, templateId: Uuid, templateVersion: Int): AddDocumentData.Content.Metadata.AddDocument =
        network.boscaGraphql.execute(AddDocument, AddDocument.Variables(parentCollectionId, templateId, templateVersion)).content.metadata.addDocument
            ?: error("No metadata returned")

    suspend fun addGuide(parentCollectionId: Uuid, templateId: Uuid, templateVersion: Int): AddGuideData.Content.Metadata.AddGuide =
        network.boscaGraphql.execute(AddGuide, AddGuide.Variables(parentCollectionId, templateId, templateVersion)).content.metadata.addGuide
            ?: error("No metadata returned")

    suspend fun findBibleReference(metadataId: Uuid, reference: String): List<FindBibleReferenceData.Content.Metadata.Bible.Find> =
        network.boscaGraphql.execute(FindBibleReference, FindBibleReference.Variables(metadataId, reference)).content.metadata?.bible?.find ?: emptyList()

    suspend fun addGuideStep(metadataId: Uuid, metadataVersion: Int, sort: Int, templateStepId: Long): AddGuideStepData.Content.Metadata.AddGuideStep =
        network.boscaGraphql.execute(AddGuideStep, AddGuideStep.Variables(metadataId, metadataVersion, sort, templateStepId)).content.metadata.addGuideStep

    suspend fun addSupplementary(supplementary: MetadataSupplementaryInput): AddMetadataSupplementaryData.Content.Metadata.AddSupplementary? =
        network.boscaGraphql.execute(AddMetadataSupplementary, AddMetadataSupplementary.Variables(supplementary)).content.metadata.addSupplementary

    suspend fun edit(id: Uuid, metadata: MetadataInput): Uuid? =
        network.boscaGraphql.execute(EditMetadata, EditMetadata.Variables(id, metadata)).content.metadata.edit.id

    suspend fun setTextContent(id: Uuid, contentType: String, content: String) {
        network.boscaGraphql.execute(SetMetadataTextContents, SetMetadataTextContents.Variables(id, contentType, content))
    }

    suspend fun setDocument(id: Uuid, version: Int, document: DocumentInput) {
        network.boscaGraphql.execute(SetDocument, SetDocument.Variables(id, version, document, CollaborationSyncMode.RESET))
    }

    suspend fun setMarkdown(id: Uuid, version: Int, title: String, markdown: String) {
        network.boscaGraphql.execute(SetMetadataMarkdown, SetMetadataMarkdown.Variables(id, version, title, markdown, CollaborationSyncMode.RESET))
    }

    suspend fun setBible(id: Uuid, version: Int, bible: BibleInput) {
        network.boscaGraphql.execute(SetBible, SetBible.Variables(id, version, bible))
    }

    suspend fun setSupplementaryTextContent(supplementaryId: Uuid, contentType: String, content: String) {
        network.boscaGraphql.execute(SetMetadataSupplementaryTextContents, SetMetadataSupplementaryTextContents.Variables(supplementaryId, contentType, content))
    }

    suspend fun setSupplementaryTextContentWithMetadata(supplementaryId: Uuid, contentType: String, content: String) {
        network.boscaGraphql.execute(SetMetadataSupplementaryTextContents, SetMetadataSupplementaryTextContents.Variables(supplementaryId, contentType, content))
    }

    suspend fun setJsonContent(id: Uuid, contentType: String, content: JsonElement) {
        network.boscaGraphql.execute(SetJsonContents, SetJsonContents.Variables(id, contentType, content))
    }

    suspend fun delete(id: Uuid): Boolean =
        network.boscaGraphql.execute(DeleteMetadata, DeleteMetadata.Variables(id)).content.metadata.delete

    suspend fun deleteSupplementary(supplementaryId: Uuid): Boolean =
        network.boscaGraphql.execute(DeleteSupplementaryMetadata, DeleteSupplementaryMetadata.Variables(supplementaryId)).content.metadata.deleteSupplementary

    suspend fun setFileContents(id: Uuid, file: Upload): Boolean =
        network.boscaGraphql.executeUpload(
            SetContents,
            SetContents.Variables(id, file.contentType, file),
            listOf(GraphQLUpload("variables.file", file)),
        ).content.metadata.setMetadataContents

    suspend fun setSupplementaryContents(supplementaryId: Uuid, file: Upload): Boolean =
        network.boscaGraphql.executeUpload(
            SetMetadataSupplementaryContents,
            SetMetadataSupplementaryContents.Variables(supplementaryId, file.contentType, file),
            listOf(GraphQLUpload("variables.file", file)),
        ).content.metadata.setSupplementaryContents

    suspend fun setSupplementaryContents(supplementaryId: Uuid, file: File, contentType: String): Boolean =
        setSupplementaryContents(supplementaryId, Upload(file.name, contentType, file.readBytes()))

    suspend fun setAttributes(id: Uuid, attributes: JsonElement?) {
        network.boscaGraphql.execute(SetMetadataAttributes, SetMetadataAttributes.Variables(id, attributes ?: JsonObject(emptyMap())))
    }

    suspend fun mergeAttributes(id: Uuid, attributes: JsonElement) {
        network.boscaGraphql.execute(MergeMetadataAttributes, MergeMetadataAttributes.Variables(id, attributes))
    }

    suspend fun mergeRelationshipAttributes(id1: Uuid, id2: Uuid, relationship: String, attributes: JsonElement) {
        network.boscaGraphql.execute(MergeMetadataRelationshipAttributes, MergeMetadataRelationshipAttributes.Variables(id1, id2, relationship, attributes))
    }

    suspend fun setSystemAttributes(id: Uuid, attributes: JsonElement?) {
        network.boscaGraphql.execute(SetMetadataSystemAttributes, SetMetadataSystemAttributes.Variables(id, attributes ?: JsonObject(emptyMap())))
    }

    suspend fun getSupplementary(id: Uuid): List<GetMetadataSupplementaryData.Content.Metadata.Supplementary> =
        network.boscaGraphql.execute(GetMetadataSupplementary, GetMetadataSupplementary.Variables(id)).content.metadata?.supplementary ?: emptyList()

    suspend fun getRelationships(id: Uuid): List<GetMetadataRelationshipsData.Content.Metadata.Relationships> =
        network.boscaGraphql.execute(GetMetadataRelationships, GetMetadataRelationships.Variables(id)).content.metadata?.relationships ?: emptyList()

    suspend fun getRelationshipsInverse(id: Uuid): List<GetMetadataRelationshipsInverseData.Content.Metadata.Relationships> =
        network.boscaGraphql.execute(GetMetadataRelationshipsInverse, GetMetadataRelationshipsInverse.Variables(id)).content.metadata?.relationships ?: emptyList()

    suspend fun addRelationship(input: MetadataRelationshipInput) {
        network.boscaGraphql.execute(AddMetadataRelationship, AddMetadataRelationship.Variables(input))
    }

    suspend fun removeRelationship(id1: Uuid, id2: Uuid, relationship: String) {
        network.boscaGraphql.execute(RemoveMetadataRelationship, RemoveMetadataRelationship.Variables(id1, id2, relationship))
    }

    suspend fun addPermission(input: PermissionInput) {
        network.boscaGraphql.execute(AddMetadataPermission, AddMetadataPermission.Variables(input))
    }

    suspend fun removePermission(input: PermissionInput) {
        network.boscaGraphql.execute(RemoveMetadataPermission, RemoveMetadataPermission.Variables(input))
    }

    suspend fun setReady(id: Uuid): Boolean =
        network.boscaGraphql.execute(SetMetadataReady, SetMetadataReady.Variables(id)).content.metadata.setMetadataReady

    suspend fun setPublic(id: Uuid, public: Boolean) {
        network.boscaGraphql.execute(SetMetadataPublic, SetMetadataPublic.Variables(id, public))
    }

    suspend fun setPublicContent(id: Uuid, public: Boolean) {
        network.boscaGraphql.execute(SetMetadataPublicContent, SetMetadataPublicContent.Variables(id, public))
    }

    suspend fun setPublicSupplementary(id: Uuid, public: Boolean) {
        network.boscaGraphql.execute(SetMetadataPublicSupplementary, SetMetadataPublicSupplementary.Variables(id, public))
    }

    suspend fun setLocked(metadataId: Uuid, version: Int, locked: Boolean) {
        network.boscaGraphql.execute(SetMetadataLocked, SetMetadataLocked.Variables(metadataId, version, locked))
    }

    suspend fun deletePermanently(id: Uuid) {
        network.boscaGraphql.execute(PermanentlyDeleteMetadata, PermanentlyDeleteMetadata.Variables(id))
    }

    suspend fun addTrait(metadataId: Uuid, traitId: String) {
        network.boscaGraphql.execute(AddMetadataTrait, AddMetadataTrait.Variables(metadataId, traitId))
    }

    suspend fun removeTrait(metadataId: Uuid, traitId: String) {
        network.boscaGraphql.execute(RemoveMetadataTrait, RemoveMetadataTrait.Variables(metadataId, traitId))
    }
}
