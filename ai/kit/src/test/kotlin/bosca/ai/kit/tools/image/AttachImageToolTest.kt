package bosca.ai.kit.tools.image

import bosca.ai.kit.agents.image.ImageServices
import bosca.ai.kit.agents.image.KitImageClient
import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.content.attributes.model.TemplateAttribute
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.content.collection.model.CollectionMetadataRelationshipInput
import bosca.content.collection.model.CollectionTemplateAttribute
import bosca.content.collection.model.CollectionType
import bosca.content.collection.service.CollectionService
import bosca.content.image.service.ImageService
import bosca.content.metadata.model.Data
import bosca.content.metadata.model.Document
import bosca.content.metadata.model.DocumentTemplateAttribute
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataRelationshipInput
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.metadata.service.DataService
import bosca.content.metadata.service.DataTemplateService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.DocumentTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTime
import bosca.storage.service.ObjectStorageService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AttachImageToolTest {

    private val imageId = Uuid.parse("10000000-0000-0000-0000-000000000001")
    private val targetId = Uuid.parse("10000000-0000-0000-0000-000000000002")
    private val templateId = Uuid.parse("10000000-0000-0000-0000-000000000003")
    private val oldImageId = Uuid.parse("10000000-0000-0000-0000-000000000004")
    private val authentication = mockk<AuthenticationContext>(relaxed = true)

    @Test
    fun `metadata attachment uses template relationship crop and target size then marks collaboration dirty`() = runTest {
        val fixture = Fixture()
        val image = image(attributes = JsonObject(mapOf("size" to JsonPrimitive("1200x800"), "jpeg" to JsonObject(emptyMap()))))
        val target = metadata(targetId, "Article", "bosca/v-document")
        val oldRelationship = MetadataRelationship(target.id, oldImageId, "image.featured", JsonObject(emptyMap()))
        val added = slot<MetadataRelationshipInput>()

        fixture.metadata(image, target)
        coEvery { fixture.documentService.getDocument(target.id, target.version) } returns
            Document(target.id, target.version, templateId, 1, "Article")
        coEvery { fixture.documentTemplateService.getTemplateAttributes(templateId, 1) } returns listOf(
            documentImageAttribute(
                key = "hero",
                relationship = "image.featured",
                targetSize = listOf(740, 416),
                aspectRatio = 16.0 / 9.0,
            )
        )
        coEvery { fixture.metadataService.getRelationships(target.id) } returns listOf(oldRelationship)
        coEvery { fixture.metadataService.addRelationship(capture(added)) } answers {
            val input = added.captured
            MetadataRelationship(input.id1, input.id2, input.relationship, input.attributes)
        }

        val result = fixture.tool.execute(
            authentication,
            AttachImageTool.Input(
                imageMetadataId = image.id.toString(),
                targetMetadataId = target.id.toString(),
                attributeKey = "hero",
            ),
        )

        assertTrue(result.success, result.error)
        assertTrue(result.attached)
        assertTrue(result.collaborationUpdated)
        assertTrue(result.optimized)
        assertEquals("hero", result.attribute?.key)
        assertEquals("image.featured", added.captured.relationship)
        val attributes = assertNotNull(added.captured.attributes).jsonObject
        val targetSize = assertNotNull(attributes["targetSize"]).jsonArray
        assertEquals(listOf(740, 416), targetSize.map { it.jsonPrimitive.toString().toInt() })
        val crop = assertNotNull(attributes["crop"]).jsonObject
        assertEquals(0.0, assertNotNull(crop["left"]).jsonPrimitive.double, 0.001)
        assertEquals(62.5, assertNotNull(crop["top"]).jsonPrimitive.double, 0.001)
        assertEquals(1200.0, assertNotNull(crop["width"]).jsonPrimitive.double, 0.001)
        assertEquals(675.0, assertNotNull(crop["height"]).jsonPrimitive.double, 0.001)
        coVerify { fixture.metadataService.removeRelationship(target.id, oldImageId, "image.featured") }
        coVerify { fixture.metadataService.markCollaborationRelationshipsDirty(target.id) }
        coVerify { fixture.imageService.optimize(image) }
        coVerify { fixture.imageService.optimize(target) }
        coVerify { fixture.metadataPermissionEvaluator.verifyAllowed(authentication, image, PermissionAction.VIEW) }
        coVerify { fixture.metadataPermissionEvaluator.verifyAllowed(authentication, target, PermissionAction.EDIT) }
    }

    @Test
    fun `collection variant attachment preserves list entries and relies on collection collaboration sync`() = runTest {
        val fixture = Fixture()
        val image = image(attributes = JsonObject(mapOf("size" to JsonPrimitive("800x600"))))
        val collection = collection(targetId)
        val variant = collectionVariant(collection, "fr")
        val existing = CollectionLanguageVariantMetadataRelationship(
            collectionId = collection.id,
            metadataId = oldImageId,
            languageTag = "fr",
            relationship = "image.gallery",
            attributes = JsonObject(emptyMap()),
        )
        val added = slot<CollectionMetadataRelationshipInput>()

        fixture.metadata(image)
        coEvery { fixture.collectionService.getById(collection.id) } returns collection
        coEvery { fixture.collectionService.getLanguageVariant(collection.id, "fr") } returns variant
        coEvery { fixture.collectionTemplateService.getCollectionTemplateAttributes(templateId, 1) } returns listOf(
            collectionImageAttribute("gallery", "image.gallery", list = true)
        )
        coEvery { fixture.collectionService.getMetadataRelationships(collection.id, "fr") } returns listOf(existing)
        coEvery { fixture.collectionService.addMetadataRelationship(capture(added)) } returns existing

        val result = fixture.tool.execute(
            authentication,
            AttachImageTool.Input(
                imageMetadataId = image.id.toString(),
                targetCollectionId = collection.id.toString(),
                targetLanguageTag = "fr",
                attributeKey = "gallery",
            ),
        )

        assertTrue(result.success, result.error)
        assertEquals("fr", result.targetLanguageTag)
        assertTrue(result.collaborationUpdated)
        assertEquals("fr", added.captured.languageTag)
        assertEquals("image.gallery", added.captured.relationship)
        val attributes = assertNotNull(added.captured.attributes).jsonObject
        val crop = assertNotNull(attributes["crop"]).jsonObject
        assertEquals(800.0, assertNotNull(crop["width"]).jsonPrimitive.double, 0.001)
        assertEquals(600.0, assertNotNull(crop["height"]).jsonPrimitive.double, 0.001)
        coVerify(exactly = 0) {
            fixture.collectionService.deleteMetadataRelationship(collection.id, "fr", any(), any())
        }
        coVerify { fixture.collectionPermissionEvaluator.verifyAllowed(authentication, variant, PermissionAction.EDIT) }
        coVerify { fixture.imageService.optimize(collection) }
        coVerify(exactly = 0) { fixture.metadataService.markCollaborationRelationshipsDirty(any()) }
    }

    @Test
    fun `omitted attribute returns compatible choices without mutating relationships`() = runTest {
        val fixture = Fixture()
        val image = image()
        val target = metadata(targetId, "Article", "bosca/v-document")
        fixture.metadata(image, target)
        coEvery { fixture.documentService.getDocument(target.id, target.version) } returns
            Document(target.id, target.version, templateId, 1, "Article")
        coEvery { fixture.documentTemplateService.getTemplateAttributes(templateId, 1) } returns listOf(
            documentImageAttribute("hero", "image.featured"),
            documentImageAttribute("card", "image.preview"),
            documentImageAttribute("not-image", "file.download", ui = AttributeUiType.FILE),
        )

        val result = fixture.tool.execute(
            authentication,
            AttachImageTool.Input(image.id.toString(), targetMetadataId = target.id.toString()),
        )

        assertFalse(result.success)
        assertTrue(assertNotNull(result.error).contains("Multiple image attributes"))
        assertEquals(listOf("hero", "card"), result.availableAttributes.map { it.key })
        coVerify(exactly = 0) { fixture.metadataService.addRelationship(any<MetadataRelationshipInput>()) }
        coVerify(exactly = 0) { fixture.imageService.optimize(any<Metadata>()) }
    }

    @Test
    fun `data metadata resolves image attributes from its data template`() = runTest {
        val fixture = Fixture()
        val image = image()
        val target = metadata(targetId, "Card", "bosca/v-data")
        fixture.metadata(image, target)
        coEvery { fixture.documentService.getDocument(target.id, target.version) } returns null
        coEvery { fixture.dataService.getData(target.id, target.version) } returns Data(target.id, target.version, templateId, 1)
        coEvery { fixture.dataTemplateService.getTemplateAttributes(templateId, 1) } returns listOf(
            documentImageAttribute("thumbnail", "image.preview")
        )
        coEvery { fixture.metadataService.getRelationships(target.id) } returns emptyList()
        coEvery { fixture.metadataService.addRelationship(any<MetadataRelationshipInput>()) } answers {
            val input = firstArg<MetadataRelationshipInput>()
            MetadataRelationship(input.id1, input.id2, input.relationship, input.attributes)
        }

        val result = fixture.tool.execute(
            authentication,
            AttachImageTool.Input(image.id.toString(), targetMetadataId = target.id.toString()),
        )

        assertTrue(result.success, result.error)
        assertEquals("thumbnail", result.attribute?.key)
        coVerify { fixture.dataTemplateService.getTemplateAttributes(templateId, 1) }
        coVerify { fixture.metadataService.markCollaborationRelationshipsDirty(target.id) }
    }

    @Test
    fun `invalid crop is normalized to the full image dimensions`() = runTest {
        val fixture = Fixture()
        val image = image()
        val target = metadata(targetId, "Article", "bosca/v-document")
        fixture.metadata(image, target)
        coEvery { fixture.documentService.getDocument(target.id, target.version) } returns
            Document(target.id, target.version, templateId, 1, "Article")
        coEvery { fixture.documentTemplateService.getTemplateAttributes(templateId, 1) } returns listOf(
            documentImageAttribute("hero", "image.featured")
        )
        coEvery { fixture.metadataService.getRelationships(target.id) } returns emptyList()
        val added = slot<MetadataRelationshipInput>()
        coEvery { fixture.metadataService.addRelationship(capture(added)) } answers {
            val input = added.captured
            MetadataRelationship(input.id1, input.id2, input.relationship, input.attributes)
        }

        val result = fixture.tool.execute(
            authentication,
            AttachImageTool.Input(
                image.id.toString(),
                targetMetadataId = target.id.toString(),
                attributeKey = "hero",
                crop = AttachImageTool.Crop(0f, 0f, 0f, 100f),
            ),
        )

        assertTrue(result.success, result.error)
        val attributes = assertNotNull(added.captured.attributes).jsonObject
        val crop = assertNotNull(attributes["crop"]).jsonObject
        assertEquals(0.0, assertNotNull(crop["top"]).jsonPrimitive.double, 0.001)
        assertEquals(0.0, assertNotNull(crop["left"]).jsonPrimitive.double, 0.001)
        assertEquals(1000.0, assertNotNull(crop["width"]).jsonPrimitive.double, 0.001)
        assertEquals(1000.0, assertNotNull(crop["height"]).jsonPrimitive.double, 0.001)
    }

    @Test
    fun `optimization and collaboration failures report a partial success without losing the attachment`() = runTest {
        val fixture = Fixture()
        val image = image()
        val target = metadata(targetId, "Article", "bosca/v-document")
        fixture.metadata(image, target)
        coEvery { fixture.documentService.getDocument(target.id, target.version) } returns
            Document(target.id, target.version, templateId, 1, "Article")
        coEvery { fixture.documentTemplateService.getTemplateAttributes(templateId, 1) } returns listOf(
            documentImageAttribute("hero", "image.featured")
        )
        coEvery { fixture.metadataService.getRelationships(target.id) } returns emptyList()
        coEvery { fixture.metadataService.addRelationship(any<MetadataRelationshipInput>()) } answers {
            val input = firstArg<MetadataRelationshipInput>()
            MetadataRelationship(input.id1, input.id2, input.relationship, input.attributes)
        }
        coEvery { fixture.imageService.optimize(target) } throws IllegalStateException("processor unavailable")
        coEvery { fixture.metadataService.markCollaborationRelationshipsDirty(target.id) } throws IllegalStateException("collaboration unavailable")

        val result = fixture.tool.execute(
            authentication,
            AttachImageTool.Input(image.id.toString(), targetMetadataId = target.id.toString()),
        )

        assertTrue(result.success)
        assertTrue(result.attached)
        assertFalse(result.optimized)
        assertFalse(result.collaborationUpdated)
        val warning = assertNotNull(result.warning)
        assertTrue(warning.contains("processor unavailable"))
        assertTrue(warning.contains("collaboration unavailable"))
    }

    @Test
    fun `permission denial is returned as a structured error`() = runTest {
        val fixture = Fixture()
        val image = image()
        val target = metadata(targetId, "Article", "bosca/v-document")
        fixture.metadata(image, target)
        coEvery {
            fixture.metadataPermissionEvaluator.verifyAllowed(authentication, target, PermissionAction.EDIT)
        } throws SecurityException("not allowed")

        val result = fixture.tool.execute(
            authentication,
            AttachImageTool.Input(image.id.toString(), targetMetadataId = target.id.toString()),
        )

        assertFalse(result.success)
        assertFalse(result.attached)
        assertTrue(assertNotNull(result.error).contains("not allowed"))
        coVerify(exactly = 0) { fixture.metadataService.addRelationship(any<MetadataRelationshipInput>()) }
    }

    private fun image(attributes: JsonObject = JsonObject(mapOf("size" to JsonPrimitive("1000x1000")))) = Metadata(
        id = imageId,
        name = "Generated Image",
        type = MetadataType.STANDARD,
        contentType = "image/png",
        contentLength = 1024,
        languageTag = "en",
        attributes = attributes,
        uploaded = OffsetDateTime.now(),
        workflowStateId = "draft",
    )

    private fun metadata(id: Uuid, name: String, contentType: String) = Metadata(
        id = id,
        name = name,
        type = MetadataType.STANDARD,
        contentType = contentType,
        contentLength = null,
        languageTag = "en",
        workflowStateId = "draft",
    )

    private fun collection(id: Uuid) = Collection(
        id = id,
        name = "Library",
        languageTag = "en",
        type = CollectionType.STANDARD,
        workflowStateId = "draft",
        templateMetadataId = templateId,
        templateMetadataVersion = 1,
    )

    private fun collectionVariant(collection: Collection, languageTag: String) = CollectionLanguageVariant(
        id = collection.id,
        languageTag = languageTag,
        name = "Bibliotheque",
        workflowStateId = "draft",
    )

    private fun documentImageAttribute(
        key: String,
        relationship: String,
        targetSize: List<Int>? = null,
        aspectRatio: Double? = null,
        ui: AttributeUiType = AttributeUiType.IMAGE,
    ): TemplateAttribute {
        val configuration = imageConfiguration(relationship, targetSize, aspectRatio)
        return TemplateAttribute(
            documentAttribute = DocumentTemplateAttribute(
                metadataId = templateId,
                version = 1,
                key = key,
                name = key.replaceFirstChar { it.uppercase() },
                description = "$key image",
                configuration = configuration,
                type = AttributeType.METADATA,
                ui = ui,
            )
        )
    }

    private fun collectionImageAttribute(key: String, relationship: String, list: Boolean) = CollectionTemplateAttribute(
        metadataId = templateId,
        version = 1,
        key = key,
        name = key.replaceFirstChar { it.uppercase() },
        description = "$key images",
        configuration = imageConfiguration(relationship),
        type = AttributeType.METADATA,
        ui = AttributeUiType.IMAGE,
        list = list,
        location = AttributeLocation.ITEM,
    )

    private fun imageConfiguration(
        relationship: String,
        targetSize: List<Int>? = null,
        aspectRatio: Double? = null,
    ): JsonObject {
        val values = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "relationship" to JsonPrimitive(relationship),
        )
        targetSize?.let { values["targetSize"] = JsonArray(it.map(::JsonPrimitive)) }
        aspectRatio?.let {
            values["aspectRatios"] = JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "name" to JsonPrimitive("default"),
                            "value" to JsonPrimitive(it),
                            "default" to JsonPrimitive(true),
                        )
                    )
                )
            )
        }
        return JsonObject(values)
    }

    private inner class Fixture {
        val metadataService = mockk<MetadataService>(relaxed = true)
        val collectionService = mockk<CollectionService>(relaxed = true)
        val documentService = mockk<DocumentService>(relaxed = true)
        val dataService = mockk<DataService>(relaxed = true)
        val documentTemplateService = mockk<DocumentTemplateService>(relaxed = true)
        val dataTemplateService = mockk<DataTemplateService>(relaxed = true)
        val collectionTemplateService = mockk<CollectionTemplateService>(relaxed = true)
        val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
        val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>(relaxed = true)
        val imageService = mockk<ImageService>(relaxed = true)
        val services = ImageServices(
            imageClient = mockk<KitImageClient>(relaxed = true),
            metadataService = metadataService,
            objectStorageService = mockk<ObjectStorageService>(relaxed = true),
            groupEvaluator = mockk<GroupEvaluator>(relaxed = true),
            collectionService = collectionService,
            documentService = documentService,
            dataService = dataService,
            documentTemplateService = documentTemplateService,
            dataTemplateService = dataTemplateService,
            collectionTemplateService = collectionTemplateService,
            metadataPermissionEvaluator = metadataPermissionEvaluator,
            collectionPermissionEvaluator = collectionPermissionEvaluator,
            imageService = imageService,
        )
        val tool = AttachImageTool(services)

        init {
            coEvery { imageService.optimize(any<Metadata>()) } returns emptyList()
            coEvery { imageService.optimize(any<Collection>()) } returns emptyList()
            coEvery { documentService.getDocument(any(), any()) } returns null
            coEvery { dataService.getData(any(), any()) } returns null
        }

        fun metadata(vararg items: Metadata) {
            val byId = items.associateBy { it.id }
            coEvery { metadataService.getById(any()) } answers { byId[firstArg()] }
        }
    }
}
