package bosca.ai.kit.tools.image

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.agents.image.ImageServices
import bosca.ai.kit.tools.KitTool
import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.content.attributes.model.TemplateAttribute
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionMetadataRelationshipInput
import bosca.content.collection.model.CollectionTemplateAttribute
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationshipInput
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Attaches an uploaded image through an image attribute declared by the target's template.
 *
 * Studio represents image attributes as `METADATA` attributes with the `IMAGE` UI. Their
 * configuration owns the relationship name, target size, and default crop aspect ratio. This tool
 * deliberately consumes the same contract and writes through the content services, then keeps the
 * target's collaboration state coherent with the persisted relationship.
 */
class AttachImageTool(
    private val services: ImageServices,
) : KitTool<AttachImageTool.Input, AttachImageTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "attach_image",
    description = "Attach an uploaded image to metadata or a collection through an IMAGE attribute " +
        "from the target's template. Omit attributeKey to discover compatible attributes; if more " +
        "than one is returned, choose the best key from its name, description, and relationship, " +
        "then call this tool again.",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The metadata ID of the uploaded image to attach.")
        val imageMetadataId: String,
        @property:LLMDescription("The target metadata ID. Set exactly one of targetMetadataId or targetCollectionId.")
        val targetMetadataId: String? = null,
        @property:LLMDescription("The target collection ID. Set exactly one of targetMetadataId or targetCollectionId.")
        val targetCollectionId: String? = null,
        @property:LLMDescription("For a translated collection, the language variant to update. Omit for the collection's base language.")
        val targetLanguageTag: String? = null,
        @property:LLMDescription("The target template's image attribute key. Omit to discover the compatible choices.")
        val attributeKey: String? = null,
        @property:LLMDescription("Optional explicit crop in source-image pixels. Omit to use the template's default aspect ratio and a centered crop.")
        val crop: Crop? = null,
    )

    @Serializable
    data class Crop(
        val top: Float,
        val left: Float,
        val width: Float,
        val height: Float,
    )

    @Serializable
    data class ImageAttribute(
        val key: String,
        val name: String,
        val description: String,
        val relationship: String,
        val list: Boolean,
        val targetSize: List<Int>? = null,
        val defaultAspectRatio: Double? = null,
    )

    @Serializable
    data class Output(
        val success: Boolean,
        val attached: Boolean = false,
        val imageMetadataId: String,
        val targetMetadataId: String? = null,
        val targetCollectionId: String? = null,
        val targetLanguageTag: String? = null,
        val attribute: ImageAttribute? = null,
        val availableAttributes: List<ImageAttribute> = emptyList(),
        val crop: Crop? = null,
        val collaborationUpdated: Boolean = false,
        val optimized: Boolean = false,
        val warning: String? = null,
        val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        return try {
            validateTarget(input)?.let { return failure(input, it) }

            val imageId = UUID.parse(input.imageMetadataId)
            val image = services.metadataService.getById(imageId)
                ?: return failure(input, "Image metadata not found: ${input.imageMetadataId}")
            if (!image.contentType.startsWith("image/")) {
                return failure(input, "Source metadata is not an image: ${image.contentType}")
            }
            if (image.uploaded == null || image.contentLength == null) {
                return failure(input, "Source image has not finished uploading: ${input.imageMetadataId}")
            }
            services.metadataPermissionEvaluator.verifyAllowed(authentication, image, PermissionAction.VIEW)

            val targetMetadataId = input.targetMetadataId
            if (targetMetadataId != null) {
                attachToMetadata(authentication, input, image, UUID.parse(targetMetadataId))
            } else {
                val targetCollectionId = input.targetCollectionId
                    ?: return failure(input, "Set exactly one of targetMetadataId or targetCollectionId.")
                attachToCollection(authentication, input, image, UUID.parse(targetCollectionId))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(input, "Unable to attach image: ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun attachToMetadata(
        authentication: AuthenticationContext,
        input: Input,
        image: Metadata,
        targetId: UUID,
    ): Output {
        val target = services.metadataService.getById(targetId)
            ?: return failure(input, "Target metadata not found: $targetId")
        services.metadataPermissionEvaluator.verifyAllowed(authentication, target, PermissionAction.EDIT)

        val attributes = metadataImageAttributes(target)
        val selected = selectAttribute(input, attributes) ?: return selectionFailure(input, attributes)

        val warnings = mutableListOf<String>()
        optimizeSource(image, warnings)
        val currentImage = services.metadataService.getById(image.id) ?: image
        val relationshipAttributes = relationshipAttributes(currentImage, selected, input.crop)

        val existing = services.metadataService.getRelationships(target.id)
        existing.filter {
            it.relationship == selected.relationship && (!selected.list || it.metadataId2 == currentImage.id)
        }.forEach {
            services.metadataService.removeRelationship(target.id, it.metadataId2, it.relationship)
        }
        services.metadataService.addRelationship(
            MetadataRelationshipInput(
                id1 = target.id,
                id2 = currentImage.id,
                relationship = selected.relationship,
                attributes = relationshipAttributes,
            )
        )

        val optimized = optimizeTarget(target, warnings)
        var collaborationUpdated = true
        try {
            // Metadata relationship jobs also set this flag, but the tool does it synchronously so
            // the editor can reconcile the selected attribute as soon as this action returns.
            services.metadataService.markCollaborationRelationshipsDirty(target.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            collaborationUpdated = false
            warnings += "The image is attached, but collaboration state could not be updated: ${e.message}"
        }

        return success(
            input = input,
            selected = selected,
            crop = relationshipAttributes.crop(),
            collaborationUpdated = collaborationUpdated,
            optimized = optimized,
            warnings = warnings,
        )
    }

    private suspend fun attachToCollection(
        authentication: AuthenticationContext,
        input: Input,
        image: Metadata,
        targetId: UUID,
    ): Output {
        val collection = services.collectionService.getById(targetId)
            ?: return failure(input, "Target collection not found: $targetId")
        val variant = collectionVariant(collection, input.targetLanguageTag)
        services.collectionPermissionEvaluator.verifyAllowed(authentication, variant ?: collection, PermissionAction.EDIT)

        val attributes = collectionImageAttributes(collection)
        val selected = selectAttribute(input, attributes) ?: return selectionFailure(input, attributes)

        val warnings = mutableListOf<String>()
        optimizeSource(image, warnings)
        val currentImage = services.metadataService.getById(image.id) ?: image
        val relationshipAttributes = relationshipAttributes(currentImage, selected, input.crop)
        val languageTag = variant?.languageTag
        val existing = if (languageTag == null) {
            services.collectionService.getMetadataRelationships(collection.id)
        } else {
            services.collectionService.getMetadataRelationships(collection.id, languageTag)
        }
        existing.filter {
            it.relationship == selected.relationship && (!selected.list || it.id2 == currentImage.id)
        }.forEach {
            if (languageTag == null) {
                services.collectionService.deleteMetadataRelationship(collection.id, it.id2, it.relationship)
            } else {
                services.collectionService.deleteMetadataRelationship(collection.id, languageTag, it.id2, it.relationship)
            }
        }
        services.collectionService.addMetadataRelationship(
            CollectionMetadataRelationshipInput(
                id = collection.id,
                languageTag = languageTag,
                metadataId = currentImage.id,
                relationship = selected.relationship,
                attributes = relationshipAttributes,
            )
        )

        // CollectionService re-encodes the collaborative attribute maps inside the same mutation.
        val optimized = optimizeTarget(collection, warnings)
        return success(
            input = input,
            selected = selected,
            crop = relationshipAttributes.crop(),
            collaborationUpdated = true,
            optimized = optimized,
            warnings = warnings,
            targetLanguageTag = languageTag ?: collection.languageTag,
        )
    }

    private suspend fun metadataImageAttributes(target: Metadata): List<ResolvedImageAttribute> {
        val document = services.documentService.getDocument(target.id, target.version)
        val documentTemplateId = document?.templateMetadataId
        val documentTemplateVersion = document?.templateMetadataVersion
        if (documentTemplateId != null && documentTemplateVersion != null) {
            return services.documentTemplateService.getTemplateAttributes(
                documentTemplateId,
                documentTemplateVersion,
            ).mapNotNull { it.toImageAttribute() }
        }

        val data = services.dataService.getData(target.id, target.version)
        val dataTemplateId = data?.templateMetadataId
        val dataTemplateVersion = data?.templateMetadataVersion
        if (dataTemplateId != null && dataTemplateVersion != null) {
            return services.dataTemplateService.getTemplateAttributes(
                dataTemplateId,
                dataTemplateVersion,
            ).mapNotNull { it.toImageAttribute() }
        }
        return emptyList()
    }

    private suspend fun collectionImageAttributes(collection: Collection): List<ResolvedImageAttribute> {
        val templateId = collection.templateMetadataId ?: return emptyList()
        val templateVersion = collection.templateMetadataVersion ?: return emptyList()
        return services.collectionTemplateService.getCollectionTemplateAttributes(templateId, templateVersion)
            .mapNotNull { it.toImageAttribute() }
    }

    private suspend fun collectionVariant(collection: Collection, requestedLanguageTag: String?): CollectionLanguageVariant? {
        if (requestedLanguageTag.isNullOrBlank() || requestedLanguageTag == collection.languageTag) return null
        return services.collectionService.getLanguageVariant(collection.id, requestedLanguageTag)
            ?: throw NoSuchElementException("Collection language variant not found: ${collection.id}/$requestedLanguageTag")
    }

    private fun selectAttribute(input: Input, attributes: List<ResolvedImageAttribute>): ResolvedImageAttribute? {
        val key = input.attributeKey?.trim()?.takeIf { it.isNotEmpty() }
        return if (key == null) attributes.singleOrNull() else attributes.firstOrNull { it.key == key }
    }

    private suspend fun optimizeSource(image: Metadata, warnings: MutableList<String>) {
        try {
            services.imageService.optimize(image)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnings += "Source image optimization did not complete: ${e.message}"
        }
    }

    private suspend fun optimizeTarget(target: Metadata, warnings: MutableList<String>): Boolean {
        return try {
            services.imageService.optimize(target)
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
            services.imageService.optimize(target)
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
        attribute: ResolvedImageAttribute,
        requestedCrop: Crop?,
    ): JsonObject {
        val attributes = image.attributes?.let { it as? JsonObject }?.toMutableMap() ?: mutableMapOf()
        attribute.targetSizeElement?.let { attributes["targetSize"] = it }
        val crop = requestedCrop?.normalize(image) ?: centeredCrop(image, attribute.defaultAspectRatio)
        crop?.let { attributes["crop"] = it.toJson() }
        return JsonObject(attributes)
    }

    private fun centeredCrop(image: Metadata, aspectRatio: Double?): Crop? {
        val dimensions = image.dimensions() ?: return null
        val (sourceWidth, sourceHeight) = dimensions
        if (aspectRatio == null) return Crop(0f, 0f, sourceWidth, sourceHeight)

        val sourceRatio = sourceWidth / sourceHeight
        return if (sourceRatio > aspectRatio) {
            val width = sourceHeight * aspectRatio
            Crop(0f, ((sourceWidth - width) / 2.0).toFloat(), width.toFloat(), sourceHeight)
        } else {
            val height = sourceWidth / aspectRatio
            Crop(((sourceHeight - height) / 2.0).toFloat(), 0f, sourceWidth, height.toFloat())
        }
    }

    private fun success(
        input: Input,
        selected: ResolvedImageAttribute,
        crop: Crop?,
        collaborationUpdated: Boolean,
        optimized: Boolean,
        warnings: List<String>,
        targetLanguageTag: String? = input.targetLanguageTag,
    ) = Output(
        success = true,
        attached = true,
        imageMetadataId = input.imageMetadataId,
        targetMetadataId = input.targetMetadataId,
        targetCollectionId = input.targetCollectionId,
        targetLanguageTag = targetLanguageTag,
        attribute = selected.output,
        availableAttributes = listOf(selected.output),
        crop = crop,
        collaborationUpdated = collaborationUpdated,
        optimized = optimized,
        warning = warnings.takeIf { it.isNotEmpty() }?.joinToString(" "),
    )

    private fun selectionFailure(input: Input, attributes: List<ResolvedImageAttribute>): Output {
        val error = when {
            attributes.isEmpty() -> "The target template has no compatible IMAGE attribute with an image.* relationship."
            input.attributeKey.isNullOrBlank() -> "Multiple image attributes are available; choose attributeKey from availableAttributes."
            else -> "Image attribute '${input.attributeKey}' is not available on the target template."
        }
        return failure(input, error, attributes)
    }

    private fun failure(
        input: Input,
        error: String,
        attributes: List<ResolvedImageAttribute> = emptyList(),
    ) = Output(
        success = false,
        imageMetadataId = input.imageMetadataId,
        targetMetadataId = input.targetMetadataId,
        targetCollectionId = input.targetCollectionId,
        targetLanguageTag = input.targetLanguageTag,
        availableAttributes = attributes.map { it.output },
        error = error,
    )

    private fun validateTarget(input: Input): String? {
        val metadata = !input.targetMetadataId.isNullOrBlank()
        val collection = !input.targetCollectionId.isNullOrBlank()
        if (metadata == collection) return "Set exactly one of targetMetadataId or targetCollectionId."
        if (metadata && !input.targetLanguageTag.isNullOrBlank()) {
            return "targetLanguageTag is only valid with targetCollectionId."
        }
        return null
    }

    private fun Crop.normalize(image: Metadata): Crop? {
        if (top.isFinite() && left.isFinite() && width.isFinite() && height.isFinite() &&
            top >= 0f && left >= 0f && width > 0f && height > 0f
        ) {
            return this
        }
        val (imageWidth, imageHeight) = image.dimensions() ?: return null
        return Crop(top = 0f, left = 0f, width = imageWidth, height = imageHeight)
    }

    private fun Metadata.dimensions(): Pair<Float, Float>? {
        return attributes?.let { it as? JsonObject }
            ?.get("size")
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
            ?.parseDimensions()
    }

    private fun Crop.toJson() = JsonObject(
        mapOf(
            "top" to JsonPrimitive(top),
            "left" to JsonPrimitive(left),
            "width" to JsonPrimitive(width),
            "height" to JsonPrimitive(height),
        )
    )

    private fun JsonObject.crop(): Crop? {
        val crop = get("crop") as? JsonObject ?: return null
        return Crop(
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

    private fun TemplateAttribute.toImageAttribute(): ResolvedImageAttribute? {
        if (type != AttributeType.METADATA || ui != AttributeUiType.IMAGE) return null
        return resolvedImageAttribute(key, name, description, list, configuration)
    }

    private fun CollectionTemplateAttribute.toImageAttribute(): ResolvedImageAttribute? {
        if (type != AttributeType.METADATA || ui != AttributeUiType.IMAGE) return null
        return resolvedImageAttribute(key, name, description, list, configuration)
    }

    private fun resolvedImageAttribute(
        key: String,
        name: String,
        description: String,
        list: Boolean,
        configuration: JsonElement?,
    ): ResolvedImageAttribute? {
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
        return ResolvedImageAttribute(
            output = ImageAttribute(key, name, description, relationship, list, targetSize, defaultAspectRatio),
            targetSizeElement = targetSizeElement,
        )
    }

    private data class ResolvedImageAttribute(
        val output: ImageAttribute,
        val targetSizeElement: JsonArray?,
    ) {
        val key get() = output.key
        val relationship get() = output.relationship
        val list get() = output.list
        val defaultAspectRatio get() = output.defaultAspectRatio
    }
}
