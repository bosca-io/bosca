package bosca.content.image.service

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.content.collection.service.CollectionService
import bosca.content.image.model.Coordinates
import bosca.content.image.model.ImageAttributes
import bosca.content.image.model.ImageResizerConfiguration
import bosca.content.image.model.ImageSize
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataSupplementaryInput
import bosca.content.metadata.service.MetadataService
import bosca.content.model.ContentRelationship
import bosca.content.timeevent.model.TimeEventMetadataRelationship
import bosca.content.timeevent.service.TimeEventService
import bosca.events.eventManager
import bosca.http.Client
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.upload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.URLEncoder
import javax.imageio.ImageIO
import kotlin.math.roundToInt

@ServiceImplementation
class ImageServiceImpl(
    private val configuration: ImageResizerConfiguration,
    private val storage: ObjectStorageService,
    private val metadataService: MetadataService,
    private val collectionService: CollectionService,
    private val timeEventService: TimeEventService,
    private val client: Client,
    private val json: Json,
    private val distributedLockFactory: DistributedLockFactory
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

    companion object {

        private val formats = arrayOf("jpeg", "webp")
        private val log = org.slf4j.LoggerFactory.getLogger(ImageServiceImpl::class.java)
    }
}