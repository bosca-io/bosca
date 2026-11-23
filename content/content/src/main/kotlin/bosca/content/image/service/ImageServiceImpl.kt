package bosca.content.image.service

import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.content.attributes.model.TemplateAttribute
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.content.collection.model.CollectionMetadataRelationshipInput
import bosca.content.collection.model.CollectionTemplateAttribute
import bosca.content.collection.service.CollectionService
import bosca.content.image.model.Coordinates
import bosca.content.image.model.ImageAttachment
import bosca.content.image.model.ImageAttributes
import bosca.content.image.model.ImageResizerConfiguration
import bosca.content.image.model.ImageSize
import bosca.content.image.model.TemplateImageAttribute
import bosca.content.image.repository.ImageAttachmentRepository
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataRelationshipInput
import bosca.content.metadata.model.MetadataSupplementaryInput
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.metadata.service.DataService
import bosca.content.metadata.service.DataTemplateService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.DocumentTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.model.ContentRelationship
import bosca.content.timeevent.model.TimeEventMetadataRelationship
import bosca.content.timeevent.service.TimeEventService
import bosca.db.transaction
import bosca.events.eventManager
import bosca.http.Client
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.upload
import java.io.File
import java.net.URLEncoder
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@ServiceImplementation
class ImageServiceImpl(
    private val configuration: ImageResizerConfiguration,
    private val storage: ObjectStorageService,
    private val metadataService: MetadataService,
    private val collectionService: CollectionService,
    private val timeEventService: TimeEventService,
    private val client: Client,
    private val json: Json,
    private val distributedLockFactory: DistributedLockFactory,
    private val documentService: DocumentService,
    private val dataService: DataService,
    private val documentTemplateService: DocumentTemplateService,
    private val dataTemplateService: DataTemplateService,
    private val collectionTemplateService: CollectionTemplateService,
    private val attachments: ImageAttachmentRepository,
) : ImageService {

    override suspend fun optimize(collection: Collection): List<UUID> {
        val lock = distributedLockFactory.create("image:optimize:collection:${collection.id}")
        return lock.withLock(1_800_000L) {
            val supplementary = mutableListOf<UUID>()
            val relationships = collectionService.getMetadataRelationships(collection.id)
            for (relationship in relationships) {
                relationship.optimize(supplementary)
            }
            collectionService.getLanguageVariants(collection.id).forEach {
                val variantRelationships = collectionService.getMetadataRelationships(it.id, it.languageTag)
                for (relationship in variantRelationships) {
                    relationship.optimize(supplementary)
                }
            }
            supplementary
        } ?: emptyList()
    }

    override suspend fun optimize(metadata: Metadata): List<UUID> {
        val lock = distributedLockFactory.create("image:optimize:metadata:${metadata.id}")
        return lock.withLock(1_800_000L) {
            val supplementary = mutableListOf<UUID>()
            if (!metadata.contentType.startsWith("image/")) {
                val relationships = metadataService.getRelationships(metadata.id)
                for (relationship in relationships) {
                    relationship.optimize(supplementary)
                }
                val timeEvents = timeEventService.getTimeEvents(metadata.id, metadata.version)
                for (timeEvent in timeEvents) {
                    val timeEventRelationships = timeEventService.getMetadataRelationships(timeEvent.id)
                    for (relationship in timeEventRelationships) {
                        relationship.optimize(supplementary)
                    }
                }
            } else {
                metadata.optimize(supplementary)
            }
            supplementary
        } ?: emptyList()
    }

    private suspend fun downloadToFile(url: String, extension: String): File = client.download(url, extension)

    private suspend fun Metadata.optimize(supplementary: MutableList<UUID>) {
        if (uploaded == null || contentLength == null) {
            error("Metadata is not uploaded")
        }
        val attributes = attributes
        val imageAttributes = if (attributes != null) {
            try {
                json.decodeFromJsonElement<ImageAttributes>(attributes)
            } catch (e: Exception) {
                log.error("Failed to decode image attributes for $id", e)
                null
            }
        } else {
            null
        }
        val formatAttributes = mutableMapOf<String, JsonElement>()
        if (imageAttributes?.size == null) {
            try {
                val path = storage.getPath(this, null)
                val url = withContext(Dispatchers.IO) {
                    storage.getSignedDownloadUrl(path, null, this@optimize, null).url
                }
                val extension = contentType.substringAfterLast("/")
                val file = downloadToFile(url, extension)
                val dimensions = getDimensions(file)
                if (dimensions != null) {
                    formatAttributes["size"] = JsonPrimitive(dimensions)
                }
                file.delete()
            } catch (e: Exception) {
                log.error("Failed to get dimensions for $id", e)
            }
        }
        formats.forEach { format ->
            val sizes = mutableMapOf<String, JsonElement>()
            for (size in configuration.sizes.filter { it.size != null }) {
                val (supplementaryId, supplementaryKey, isNew) = resize(this, format, size)
                supplementary += supplementaryId
                if (isNew) {
                    sizes[size.name.replace("-original", "")] = JsonPrimitive(supplementaryKey)
                }
            }
            if (sizes.isEmpty()) return@forEach
            formatAttributes[format] = JsonObject(sizes)
        }
        if (formatAttributes.isNotEmpty()) {
            val formatObject = JsonObject(formatAttributes)
            eventManager().disabled {
                metadataService.mergeAttributes(this, formatObject)
            }
        }
    }

    private suspend fun ContentRelationship.optimize(supplementary: MutableList<UUID>) {
        val relationshipMetadata = metadataService.getById(id2) ?: return
        if (relationshipMetadata.contentType.startsWith("image/") && !relationshipMetadata.contentType.startsWith("image/svg")) {
            if (relationshipMetadata.uploaded == null || relationshipMetadata.contentLength == null) {
                error("Metadata is not uploaded")
            }
            val attributes = attributes
            val attr = if (attributes != null) {
                json.decodeFromJsonElement<ImageAttributes>(attributes)
            } else {
                null
            }
            val formatAttributes = mutableMapOf<String, JsonElement>()
            val crop = attr?.crop ?: Coordinates()
            var errors = false
            formats.forEach { format ->
                val size = ImageSize(
                    "${crop.width.roundToInt()}x${crop.height.roundToInt()}-${crop.top.roundToInt()}-${crop.left.roundToInt()}-cropped",
                    100f,
                    crop
                )
                val (parentSupplementaryId, _, _) = resize(relationshipMetadata, format, size)
                supplementary += parentSupplementaryId
                val ratioSizes = configuration.sizes.filter { it.size == null }
                val sizes = mutableMapOf<String, JsonElement>()
                for (defaultSize in ratioSizes) {
                    try {
                        val (supplementaryId, supplementaryKey, isNew) = resize(relationshipMetadata, format, size.copy(name = size.name + "-" + defaultSize.name, size = null, ratio = defaultSize.ratio), parentSupplementaryId)
                        supplementary += supplementaryId
                        val keyExists = try {
                            attributes?.jsonObject?.get(format)?.jsonObject?.containsKey(defaultSize.name.replace("-original", "")) ?: false
                        } catch (_: Exception) {
                            false
                        }
                        if (isNew || !keyExists) {
                            sizes[defaultSize.name.replace("-original", "")] = JsonPrimitive(supplementaryKey)
                        }
                    } catch (e: Exception) {
                        log.error("Failed to resize $size for ${relationshipMetadata.id}", e)
                        errors = true
                    }
                }
                if (sizes.isNotEmpty()) {
                    formatAttributes[format] = JsonObject(sizes)
                }
            }
            if (formatAttributes.isNotEmpty()) {
                val formatObject = JsonObject(formatAttributes)
                eventManager().disabled {
                    when (this) {
                        is CollectionMetadataRelationship -> {
                            collectionService.mergeMetadataRelationshipAttributes(collectionId, metadataId, relationship, formatObject)
                        }
                        is CollectionLanguageVariantMetadataRelationship -> {
                            collectionService.mergeMetadataRelationshipAttributes(collectionId, languageTag, metadataId, relationship, formatObject)
                        }
                        is MetadataRelationship -> {
                            metadataService.mergeAttributes(metadataId1, metadataId2, relationship, formatObject)
                        }
                        is TimeEventMetadataRelationship -> {
                            timeEventService.mergeMetadataRelationshipAttributes(timeEventId, metadataId, relationship, formatObject)
                        }
                    }

                }
            }
            if (errors) throw Exception("Failed to resize images for ${relationshipMetadata.id}")
        }
    }

    private fun getDimensions(file: File): String? {
        try {
            val iter = ImageIO.getImageReadersBySuffix(file.extension)
            if (iter.hasNext()) {
                val reader = iter.next()
                try {
                    val stream = ImageIO.createImageInputStream(file)
                    reader.input = stream
                    val width = reader.getWidth(reader.minIndex)
                    val height = reader.getHeight(reader.minIndex)
                    stream.close()
                    return "${width}x${height}"
                } catch (e: Exception) {
                    log.error("Error reading image dimensions", e)
                } finally {
                    reader.dispose()
                }
            }
        } catch (e: Exception) {
            log.error("Error getting image readers", e)
        }
        return null
    }

    private suspend fun resize(
        metadata: Metadata,
        format: String,
        size: ImageSize,
        parentSupplementaryId: UUID? = null
    ): Triple<UUID, String, Boolean> {
        val supplementaryKey = "${size.name}-${size.ratio}-$format"
        var supplementary = metadataService.getSupplementary(metadata.id).find { it.key == supplementaryKey }

        if (supplementary != null && supplementary.contentLength != null && supplementary.uploaded != null) {
            log.info("** not resizing $size - $format for ${metadata.id} because it is already uploaded")
            return Triple(supplementary.id, supplementary.key, false)
        }

        log.info("&& resizing $size - $format for ${metadata.id}")

        val imageProcessorUrl = configuration.url
        val path = storage.getPath(metadata, parentSupplementaryId)
        val url = withContext(Dispatchers.IO) {
            URLEncoder.encode(storage.getSignedDownloadUrl(path, null, metadata, parentSupplementaryId).url, "UTF-8")
        }
        val resized = if (size.size != null && size.size?.isZero != true) {
            "$imageProcessorUrl/image?u=$url&f=$format&w=${size.size?.width?.roundToInt()}&h=${size.size?.height?.roundToInt()}&l=${size.size?.left?.roundToInt()}&t=${size.size?.top?.roundToInt()}"
        } else {
            val ratio = size.ratio / 100f
            "$imageProcessorUrl/image?u=$url&f=$format&pw=${ratio}&ph=${ratio}"
        }
        val contentType = if (format == "jpeg") "image/jpg" else "image/webp"

        log.info("Resizing $size for ${metadata.id}")

        val file = downloadToFile(resized, format)
        if (supplementary == null) {
            supplementary = metadataService.addSupplementary(
                MetadataSupplementaryInput(
                    name = supplementaryKey,
                    contentType = contentType,
                    key = supplementaryKey,
                    metadataId = metadata.id
                )
            )
        }

        val contentLength = storage.upload(metadata, supplementary.id, file)
        metadataService.setSupplementaryUploaded(supplementary.id, contentType, contentLength)
        return Triple(supplementary.id, supplementary.key, true)
    }

    override suspend fun attach(image: Metadata, target: Metadata, attributeKey: String?, crop: Coordinates?): ImageAttachment {
        validateImage(image)
        val attributes = metadataImageAttributes(target)
        val selected = selectAttribute(attributeKey, attributes)
            ?: return selectionFailure(attributeKey, attributes)
        val warnings = mutableListOf<String>()
        optimizeSource(image, warnings)
        val source = metadataService.getById(image.id) ?: image
        val relationshipAttributes = relationshipAttributes(source, selected, crop)
        transaction {
            check(attachments.lock("metadata:${target.id}"))
            metadataService.removeFromCache(target)
            val existing = metadataService.getRelationships(target.id)
            existing.filter { it.relationship == selected.relationship && (!selected.list || it.metadataId2 == source.id) }
                .forEach { metadataService.removeRelationship(target.id, it.metadataId2, it.relationship) }
            metadataService.addRelationship(MetadataRelationshipInput(target.id, source.id, selected.relationship, relationshipAttributes))
        }
        val optimized = optimizeTarget(target, warnings)
        var collaborationUpdated = true
        try {
            metadataService.markCollaborationRelationshipsDirty(target.id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            collaborationUpdated = false
            warnings += "The image is attached, but collaboration state could not be updated: ${failure.message}"
        }
        return attachment(selected, relationshipAttributes.crop(), collaborationUpdated, optimized, warnings)
    }

    override suspend fun attach(image: Metadata, target: Collection, languageTag: String?, attributeKey: String?, crop: Coordinates?): ImageAttachment {
        validateImage(image)
        val variant = languageTag?.takeIf { it.isNotBlank() && it != target.languageTag }?.let {
            collectionService.getLanguageVariant(target.id, it) ?: error("Collection language variant not found")
        }
        val attributes = collectionImageAttributes(target)
        val selected = selectAttribute(attributeKey, attributes)
            ?: return selectionFailure(attributeKey, attributes)
        val warnings = mutableListOf<String>()
        optimizeSource(image, warnings)
        val source = metadataService.getById(image.id) ?: image
        val relationshipAttributes = relationshipAttributes(source, selected, crop)
        transaction {
            check(attachments.lock("collection:${target.id}"))
            collectionService.removeFromCache(target.id)
            val existing = if (variant == null) collectionService.getMetadataRelationships(target.id)
                else collectionService.getMetadataRelationships(target.id, variant.languageTag)
            existing.filter { it.relationship == selected.relationship && (!selected.list || it.id2 == source.id) }.forEach {
                if (variant == null) collectionService.deleteMetadataRelationship(target.id, it.id2, it.relationship)
                else collectionService.deleteMetadataRelationship(target.id, variant.languageTag, it.id2, it.relationship)
            }
            collectionService.addMetadataRelationship(CollectionMetadataRelationshipInput(
                target.id, variant?.languageTag, source.id, selected.relationship, relationshipAttributes,
            ))
        }
        return attachment(selected, relationshipAttributes.crop(), true, optimizeTarget(target, warnings), warnings)
            .copy(targetLanguageTag = variant?.languageTag ?: target.languageTag)
    }

    private fun validateImage(image: Metadata) {
        require(image.contentType.startsWith("image/")) { "Source metadata is not an image" }
        require(image.uploaded != null && image.contentLength != null) { "Source image has not finished uploading" }
    }

    private fun selectAttribute(key: String?, attributes: List<TemplateImageAttribute>): TemplateImageAttribute? =
        if (key.isNullOrBlank()) attributes.singleOrNull() else attributes.firstOrNull { it.key == key.trim() }

    private fun selectionFailure(key: String?, attributes: List<TemplateImageAttribute>) = ImageAttachment(
        success = false,
        availableAttributes = attributes,
        error = when {
            attributes.isEmpty() -> "The target template has no compatible IMAGE attribute with an image.* relationship."
            key.isNullOrBlank() -> "Multiple image attributes are available; choose attributeKey from availableAttributes."
            else -> "Image attribute '$key' is not available on the target template."
        },
    )

    private fun attachment(attribute: TemplateImageAttribute, crop: Coordinates?, collaborationUpdated: Boolean, optimized: Boolean, warnings: List<String>) =
        ImageAttachment(success = true, attached = true, attribute = attribute, availableAttributes = listOf(attribute),
            crop = crop, collaborationUpdated = collaborationUpdated, optimized = optimized,
            warning = warnings.takeIf { it.isNotEmpty() }?.joinToString(" "))

    private suspend fun metadataImageAttributes(target: Metadata): List<TemplateImageAttribute> {
        val document = documentService.getDocument(target.id, target.version)
        val documentTemplateId = document?.templateMetadataId
        val documentTemplateVersion = document?.templateMetadataVersion
        if (documentTemplateId != null && documentTemplateVersion != null) {
            return documentTemplateService.getTemplateAttributes(
                documentTemplateId,
                documentTemplateVersion,
            ).mapNotNull { it.toImageAttribute() }
        }

        val data = dataService.getData(target.id, target.version)
        val dataTemplateId = data?.templateMetadataId
        val dataTemplateVersion = data?.templateMetadataVersion
        if (dataTemplateId != null && dataTemplateVersion != null) {
            return dataTemplateService.getTemplateAttributes(
                dataTemplateId,
                dataTemplateVersion,
            ).mapNotNull { it.toImageAttribute() }
        }
        return emptyList()
    }

    private suspend fun collectionImageAttributes(collection: Collection): List<TemplateImageAttribute> {
        val templateId = collection.templateMetadataId ?: return emptyList()
        val templateVersion = collection.templateMetadataVersion ?: return emptyList()
        return collectionTemplateService.getCollectionTemplateAttributes(templateId, templateVersion)
            .mapNotNull { it.toImageAttribute() }
    }

    private suspend fun optimizeSource(image: Metadata, warnings: MutableList<String>) {
        try {
            optimize(image)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnings += "Source image optimization did not complete: ${e.message}"
        }
    }

    private suspend fun optimizeTarget(target: Metadata, warnings: MutableList<String>): Boolean {
        return try {
            optimize(target)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnings += "The image is attached, but relationship optimization did not complete: ${e.message}"
            false
        }
    }

    private suspend fun optimizeTarget(target: Collection, warnings: MutableList<String>): Boolean {
        return try {
            optimize(target)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnings += "The image is attached, but relationship optimization did not complete: ${e.message}"
            false
        }
    }

    private fun relationshipAttributes(
        image: Metadata,
        attribute: TemplateImageAttribute,
        requestedCrop: Coordinates?,
    ): JsonObject {
        val attributes = image.attributes?.let { it as? JsonObject }?.toMutableMap() ?: mutableMapOf()
        attribute.targetSize?.let { attributes["targetSize"] = JsonArray(it.map(::JsonPrimitive)) }
        val crop = requestedCrop?.normalize(image) ?: centeredCrop(image, attribute.defaultAspectRatio)
        crop?.let { attributes["crop"] = it.toJson() }
        return JsonObject(attributes)
    }

    private fun centeredCrop(image: Metadata, aspectRatio: Double?): Coordinates? {
        val dimensions = image.dimensions() ?: return null
        val (sourceWidth, sourceHeight) = dimensions
        if (aspectRatio == null) return Coordinates(0f, 0f, sourceWidth, sourceHeight)

        val sourceRatio = sourceWidth / sourceHeight
        return if (sourceRatio > aspectRatio) {
            val width = sourceHeight * aspectRatio
            Coordinates(0f, ((sourceWidth - width) / 2.0).toFloat(), width.toFloat(), sourceHeight)
        } else {
            val height = sourceWidth / aspectRatio
            Coordinates(((sourceHeight - height) / 2.0).toFloat(), 0f, sourceWidth, height.toFloat())
        }
    }

    private fun Coordinates.normalize(image: Metadata): Coordinates? {
        if (top.isFinite() && left.isFinite() && width.isFinite() && height.isFinite() &&
            top >= 0f && left >= 0f && width > 0f && height > 0f
        ) {
            return this
        }
        val (imageWidth, imageHeight) = image.dimensions() ?: return null
        return Coordinates(top = 0f, left = 0f, width = imageWidth, height = imageHeight)
    }

    private fun Metadata.dimensions(): Pair<Float, Float>? {
        return attributes?.let { it as? JsonObject }
            ?.get("size")
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
            ?.parseDimensions()
    }

    private fun Coordinates.toJson() = JsonObject(
        mapOf(
            "top" to JsonPrimitive(top),
            "left" to JsonPrimitive(left),
            "width" to JsonPrimitive(width),
            "height" to JsonPrimitive(height),
        )
    )

    private fun JsonObject.crop(): Coordinates? {
        val crop = get("crop") as? JsonObject ?: return null
        return Coordinates(
            top = crop["top"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: return null,
            left = crop["left"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: return null,
            width = crop["width"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: return null,
            height = crop["height"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: return null,
        )
    }

    private fun String.parseDimensions(): Pair<Float, Float>? {
        val parts = split('x', limit = 2)
        if (parts.size != 2) return null
        val width = parts[0].toFloatOrNull() ?: return null
        val height = parts[1].toFloatOrNull() ?: return null
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
        return width to height
    }

    private fun TemplateAttribute.toImageAttribute(): TemplateImageAttribute? {
        if (type != AttributeType.METADATA || ui != AttributeUiType.IMAGE) return null
        return resolvedImageAttribute(key, name, description, list, configuration)
    }

    private fun CollectionTemplateAttribute.toImageAttribute(): TemplateImageAttribute? {
        if (type != AttributeType.METADATA || ui != AttributeUiType.IMAGE) return null
        return resolvedImageAttribute(key, name, description, list, configuration)
    }

    private fun resolvedImageAttribute(
        key: String,
        name: String,
        description: String,
        list: Boolean,
        configuration: JsonElement?,
    ): TemplateImageAttribute? {
        val config = configuration as? JsonObject ?: return null
        val relationship = (config["relationship"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.startsWith("image.") }
            ?: return null
        val targetSizeElement = (config["targetSize"] as? JsonArray)?.takeIf { array ->
            array.size == 2 && array.all {
                (it as? JsonPrimitive)?.intOrNull?.let { value -> value > 0 } == true
            }
        }
        val targetSize = targetSizeElement?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        val defaultAspectRatio = (config["aspectRatios"] as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.takeIf { (it["default"] as? JsonPrimitive)?.booleanOrNull == true }
            ?.get("value")
            ?.let { it as? JsonPrimitive }
            ?.doubleOrNull
            ?.takeIf { it.isFinite() && it > 0.0 }
        return TemplateImageAttribute(key, name, description, relationship, list, targetSize, defaultAspectRatio)
    }

    companion object {

        private val formats = arrayOf("jpeg", "webp")
        private val log = org.slf4j.LoggerFactory.getLogger(ImageServiceImpl::class.java)
    }
}
