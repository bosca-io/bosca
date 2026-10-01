package bosca.cli.data

import bosca.cli.api.ContentCollections
import bosca.cli.api.ContentMetadata
import bosca.cli.data.ContentConverters.toCollectionType
import bosca.cli.data.ContentConverters.toContainerInput
import bosca.cli.data.ContentConverters.toDataType
import bosca.cli.data.ContentConverters.toGuideType
import bosca.cli.data.ContentConverters.toOrderingInput
import bosca.cli.data.ContentConverters.toTemplateAttributeInput
import bosca.cli.data.model.*
import bosca.graphql.gen.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.uuid.Uuid

/**
 * Reads an [InstallManifest] and provisions its templates, collections,
 * metadata items, and relationships into a running Bosca instance via
 * the GraphQL API.
 *
 * Tracks created resource IDs by their manifest ref so that later
 * entries can reference earlier ones (e.g. a blog post referencing
 * its parent collection or document template).
 */
class DataInstaller(
    private val metadata: ContentMetadata,
    private val collections: ContentCollections,
) {

    private val refToId = mutableMapOf<String, Uuid>()
    private val refToVersion = mutableMapOf<String, Int>()

    /**
     * Parse the manifest from the given file and provision all content. The
     * [network] passed to the API clients must already be authenticated (the
     * caller signs in via [bosca.cli.api.CliAuth] before invoking this).
     */
    suspend fun install(manifestFile: File) {
        val json = Json { ignoreUnknownKeys = true }
        val manifest = json.decodeFromString<InstallManifest>(manifestFile.readText())
        val baseDir = manifestFile.parentFile ?: File(".")
        install(manifest, baseDir)
    }

    /**
     * Provision all content defined in the manifest into the Bosca instance.
     * Creates items in dependency order: templates first, then collections,
     * then metadata items, and finally relationships between them.
     */
    suspend fun install(manifest: InstallManifest, baseDir: File) {
        println("Installing: ${manifest.name} v${manifest.version}")
        println("  ${manifest.description}")

        for (template in manifest.documentTemplates) {
            installDocumentTemplate(template)
        }

        for (template in manifest.guideTemplates) {
            installGuideTemplate(template)
        }

        for (template in manifest.dataTemplates) {
            installDataTemplate(template)
        }

        for (template in manifest.collectionTemplates) {
            installCollectionTemplate(template)
        }

        for (collection in manifest.collections) {
            installCollection(collection)
        }

        for (meta in manifest.metadata) {
            installMetadata(meta, baseDir)
        }

        for (relationship in manifest.relationships) {
            installRelationship(relationship)
        }

        println("Installation complete.")
    }

    private suspend fun installDocumentTemplate(template: DocumentTemplateDefinition) {
        println("  Creating document template: ${template.name}")
        val templateInput = DocumentTemplateInput(
            attributes = template.attributes.map { it.toTemplateAttributeInput() },
            configuration = template.configuration,
            containers = template.containers.map { it.toContainerInput() },
            content = JsonObject(mapOf("document" to (template.defaultContent ?: JsonObject(emptyMap())))),
            defaultAttributes = template.defaultAttributes,
            schema = template.schema,
        )
        val metadataInput = MetadataInput(
            name = template.name,
            contentType = template.contentType,
            languageTag = template.languageTag,
            slug = template.slug,
            documentTemplate = templateInput,
            traitIds = template.traitIds,
            attributes = templateEditorAttributes("Document"),
        )
        val id = metadata.add(metadataInput) ?: error("Failed to create document template: ${template.name}")
        refToId[template.ref] = id
        refToVersion[template.ref] = 1
        metadata.setReady(id)
        println("    Created: $id")
    }

    private suspend fun installGuideTemplate(template: GuideTemplateDefinition) {
        println("  Creating guide template: ${template.name}")
        val stepsInput = template.steps.map { step ->
            val stepTemplateId = refToId[step.templateRef]
                ?: error("Step template ref '${step.templateRef}' not found for guide template '${template.name}'")
            val stepTemplateVersion = refToVersion[step.templateRef]
                ?: error("Step template ref '${step.templateRef}' version not found for guide template '${template.name}'")
            GuideTemplateStepInput(
                templateMetadataId = stepTemplateId,
                templateMetadataVersion = stepTemplateVersion,
                modules = step.modules.map { module ->
                    val moduleTemplateId = refToId[module.templateRef]
                        ?: error("Module template ref '${module.templateRef}' not found for guide template '${template.name}'")
                    val moduleTemplateVersion = refToVersion[module.templateRef]
                        ?: error("Module template ref '${module.templateRef}' version not found for guide template '${template.name}'")
                    GuideTemplateStepModuleInput(
                        templateMetadataId = moduleTemplateId,
                        templateMetadataVersion = moduleTemplateVersion,
                    )
                },
            )
        }
        val guideTemplateInput = GuideTemplateInput(
            configuration = template.configuration,
            defaultAttributes = template.defaultAttributes,
            rrule = template.rrule,
            steps = stepsInput,
            type = template.type.toGuideType(),
        )
        val documentTemplateInput = DocumentTemplateInput(
            attributes = emptyList(),
            content = JsonObject(emptyMap()),
            configuration = null,
            containers = emptyList<DocumentTemplateContainerInput>(),
            defaultAttributes = null,
            schema = null,
        )
        val metadataInput = MetadataInput(
            name = template.name,
            contentType = template.contentType,
            languageTag = template.languageTag,
            slug = template.slug,
            documentTemplate = documentTemplateInput,
            guideTemplate = guideTemplateInput,
            traitIds = template.traitIds,
            attributes = templateEditorAttributes("Guide"),
        )
        val id = metadata.add(metadataInput) ?: error("Failed to create guide template: ${template.name}")
        refToId[template.ref] = id
        refToVersion[template.ref] = 1

        for ((index, attr) in template.attributes.withIndex()) {
            metadata.addGuideTemplateAttribute(id, 1, attr.toTemplateAttributeInput(), index)
        }

        metadata.setReady(id)
        println("    Created: $id")
    }

    private suspend fun installCollectionTemplate(template: CollectionTemplateDefinition) {
        println("  Creating collection template: ${template.name}")
        val filtersInput = if (template.filters.isNotEmpty()) {
            CollectionTemplateFiltersInput(
                filters = template.filters.map { CollectionTemplateFilterInput(filter = it.filter, name = it.name) }
            )
        } else {
            null
        }
        val orderingInput = if (template.ordering.isNotEmpty()) {
            template.ordering.map { it.toOrderingInput() }
        } else {
            null
        }
        val templateInput = CollectionTemplateInput(
            attributes = template.attributes.map { it.toTemplateAttributeInput() },
            configuration = template.configuration,
            defaultAttributes = template.defaultAttributes,
            filters = filtersInput,
            ordering = orderingInput,
        )
        val metadataInput = MetadataInput(
            name = template.name,
            contentType = template.contentType,
            languageTag = template.languageTag,
            slug = template.slug,
            collectionTemplate = templateInput,
            traitIds = template.traitIds,
            attributes = templateEditorAttributes("Collection"),
        )
        val id = metadata.add(metadataInput) ?: error("Failed to create collection template: ${template.name}")
        refToId[template.ref] = id
        refToVersion[template.ref] = 1
        metadata.setReady(id)
        println("    Created: $id")
    }

    private suspend fun installDataTemplate(template: DataTemplateDefinition) {
        println("  Creating data template: ${template.name}")
        val dataTemplateInput = DataTemplateInput(
            attributes = template.attributes.map { it.toTemplateAttributeInput() },
            defaultAttributes = template.defaultAttributes,
            type = template.type.toDataType(),
        )
        val metadataInput = MetadataInput(
            name = template.name,
            contentType = template.contentType,
            languageTag = template.languageTag,
            slug = template.slug,
            dataTemplate = dataTemplateInput,
            traitIds = template.traitIds,
            attributes = templateEditorAttributes("Data"),
        )
        val id = metadata.add(metadataInput) ?: error("Failed to create data template: ${template.name}")
        refToId[template.ref] = id
        refToVersion[template.ref] = 1
        metadata.setReady(id)
        println("    Created: $id")
    }

    private suspend fun installCollection(collection: CollectionDefinition) {
        println("  Creating collection: ${collection.name}")
        val parentId = collection.parentRef?.let {
            refToId[it] ?: error("Parent ref '$it' not found for collection '${collection.name}'")
        }
        val templateId = collection.templateRef?.let {
            refToId[it] ?: error("Template ref '$it' not found for collection '${collection.name}'")
        }
        val templateVersion = collection.templateRef?.let { refToVersion[it] }
        val collectionInput = CollectionInput(
            name = collection.name,
            description = collection.description,
            slug = collection.slug,
            collectionType = collection.collectionType.toCollectionType(),
            parentCollectionId = parentId?.toString(),
            templateMetadataId = templateId,
            templateMetadataVersion = templateVersion,
            attributes = collection.attributes,
            traitIds = collection.traitIds,
        )
        val id = collections.add(collectionInput) ?: error("Failed to create collection: ${collection.name}")
        refToId[collection.ref] = id
        if (collection.public) {
            collections.setPublic(id, true)
        }
        if (collection.publicList) {
            collections.setPublicList(id, true)
        }
        collections.setReady(id)
        println("    Created: $id")
    }

    private suspend fun installMetadata(meta: MetadataDefinition, baseDir: File) {
        println("  Creating metadata: ${meta.name}")
        val parentId = meta.parentRef?.let {
            refToId[it] ?: error("Parent ref '$it' not found for metadata '${meta.name}'")
        }
        val templateId = meta.documentTemplateRef?.let {
            refToId[it] ?: error("Document template ref '$it' not found for metadata '${meta.name}'")
        }
        val templateVersion = meta.documentTemplateRef?.let { refToVersion[it] }

        val documentInput = meta.document?.let {
            DocumentInput(
                title = it.title,
                content = it.content,
                templateMetadataId = templateId,
                templateMetadataVersion = templateVersion,
            )
        }
        val metadataInput = MetadataInput(
            name = meta.name,
            contentType = meta.contentType,
            languageTag = meta.languageTag,
            slug = meta.slug,
            parentCollectionId = parentId?.let { it },
            document = documentInput,
            attributes = meta.attributes,
            traitIds = meta.traitIds,
        )
        val id = metadata.add(metadataInput) ?: error("Failed to create metadata: ${meta.name}")
        refToId[meta.ref] = id

        if (meta.textContent != null) {
            metadata.setTextContent(id, meta.contentType, meta.textContent)
        }

        if (meta.contentFile != null) {
            val file = File(baseDir, meta.contentFile)
            if (file.exists()) {
                metadata.setFileContents(id, bosca.graphql.client.Upload(file.name, meta.contentType, file.readBytes()))
                println("    Uploaded content: ${meta.contentFile}")
            } else {
                println("    WARNING: Content file not found: ${file.absolutePath}")
            }
        }

        for (supp in meta.supplementary) {
            installSupplementary(id, supp, baseDir)
        }

        if (meta.public) {
            metadata.setPublic(id, true)
        }
        if (meta.publicContent) {
            metadata.setPublicContent(id, true)
        }
        if (meta.publicSupplementary) {
            metadata.setPublicSupplementary(id, true)
        }

        metadata.setReady(id)
        println("    Created: $id")
    }

    private suspend fun installSupplementary(metadataId: Uuid, supp: SupplementaryDefinition, baseDir: File) {
        println("    Adding supplementary: ${supp.key}")
        val supplementaryInput = MetadataSupplementaryInput(
            metadataId = metadataId,
            key = supp.key,
            name = supp.name,
            contentType = supp.contentType,
            attributes = supp.attributes,
            contentLength = supp.file?.let { File(baseDir, it).length().toInt() },
            planId = metadataId,
        )
        val supplementary = metadata.addSupplementary(supplementaryInput)
            ?: error("Failed to add supplementary '${supp.key}' to $metadataId")
        val supplementaryId = supplementary.id

        if (supp.textContent != null) {
            metadata.setSupplementaryTextContent(supplementaryId, supp.contentType, supp.textContent)
        }

        if (supp.file != null) {
            val file = File(baseDir, supp.file)
            if (file.exists()) {
                metadata.setSupplementaryContents(supplementaryId, file, supp.contentType)
                println("      Uploaded: ${supp.file}")
            } else {
                println("      WARNING: File not found: ${file.absolutePath}")
            }
        }
    }

    private suspend fun installRelationship(rel: RelationshipDefinition) {
        val fromId = refToId[rel.fromRef]
            ?: error("Relationship fromRef '${rel.fromRef}' not found")
        val toId = refToId[rel.toRef]
            ?: error("Relationship toRef '${rel.toRef}' not found")
        println("  Creating relationship: ${rel.fromRef} -[${rel.relationship}]-> ${rel.toRef}")
        metadata.addRelationship(
            MetadataRelationshipInput(
                id1 = fromId,
                id2 = toId,
                relationship = rel.relationship,
                attributes = (rel.attributes.takeIf { it is JsonObject } ?: JsonObject(emptyMap())),
            )
        )
    }

    private fun templateEditorAttributes(templateType: String) =
        ContentConverters.templateEditorAttributes(templateType)
}
