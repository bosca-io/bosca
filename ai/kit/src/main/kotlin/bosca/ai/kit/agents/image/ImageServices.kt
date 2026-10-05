package bosca.ai.kit.agents.image

import bosca.content.collection.service.CollectionService
import bosca.content.image.service.ImageService
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.metadata.service.DataService
import bosca.content.metadata.service.DataTemplateService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.DocumentTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.service.GroupEvaluator
import bosca.storage.service.ObjectStorageService

/**
 * What the [ImageAgent]'s tools need, bundled so the image capability threads a single dependency
 * through the planner/action chain. Holds the Gemini [imageClient], plus the platform services used to
 * persist a generated/edited image ([metadataService]/[objectStorageService]), attach it through
 * template attributes, synchronize collaboration state, optimize it, and authorize the caller.
 */
class ImageServices(
    val imageClient: KitImageClient,
    val metadataService: MetadataService,
    val objectStorageService: ObjectStorageService,
    val groupEvaluator: GroupEvaluator,
    val collectionService: CollectionService,
    val documentService: DocumentService,
    val dataService: DataService,
    val documentTemplateService: DocumentTemplateService,
    val dataTemplateService: DataTemplateService,
    val collectionTemplateService: CollectionTemplateService,
    val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    val collectionPermissionEvaluator: CollectionPermissionEvaluator,
    val imageService: ImageService,
)
