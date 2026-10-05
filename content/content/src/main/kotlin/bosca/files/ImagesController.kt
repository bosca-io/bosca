//package bosca.files
//
//import bosca.content.metadata.model.Metadata
//import bosca.content.metadata.service.MetadataService
//import bosca.security.model.PermissionAction
//import bosca.security.service.GroupEvaluator
//import bosca.security.service.MetadataPermissionEvaluator
//import bosca.serialization.UUID
//import bosca.slug.service.SlugService
//import bosca.storage.ObjectStorageService
//import bosca.storage.downloadAsResource
//import org.springframework.core.io.Resource
//import org.springframework.http.HttpHeaders
//import org.springframework.http.HttpStatus
//import org.springframework.http.ResponseEntity
//
//import org.springframework.web.bind.annotation.GetMapping
//import org.springframework.web.bind.annotation.PathVariable
//import org.springframework.web.bind.annotation.RequestMapping
//import org.springframework.web.bind.annotation.RequestParam
//import org.springframework.web.bind.annotation.RestController
//
//@RestController
//@RequestMapping("/content")
//class ImagesController(
//    private val metadataService: MetadataService,
//    private val slugService: SlugService,
//    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
//    private val groupEvaluator: GroupEvaluator,
//    private val storage: ObjectStorageService,
//) {
//
//    private suspend fun verifyHasPermission(authentication: AuthenticationContext?, metadata: Metadata, supplementary: Boolean) {
//        val hasPermission = try {
//            if (supplementary) {
//                metadataPermissionEvaluator.isSupplementaryAllowed(
//                    authentication,
//                    metadata,
//                    PermissionAction.VIEW
//                )
//            } else {
//                metadataPermissionEvaluator.isContentAllowed(authentication, metadata, PermissionAction.VIEW)
//            }
//        } catch (e: Exception) {
//            error("Error getting supplementary: ${e.localizedMessage}")
//        }
//        if (!hasPermission) {
//            groupEvaluator.verifyHasAdminGroup(authentication)
//        }
//    }
//
//    private suspend fun getImage(
//        authentication: AuthenticationContext?,
//        metadata: Metadata,
//        key: String?,
//        download: Boolean?
//    ): ResponseEntity<Resource> {
//        val (supplementaryId, contentType, contentLength) = if (key != null) {
//            val resized = try {
//                metadataService.getSupplementaryByMetadataAndKey(metadata.id ?: error("missing id"), key)
//            } catch (e: Exception) {
//                error("Error getting supplementary: ${e.localizedMessage}")
//            }
//            if (resized != null) {
//                verifyHasPermission(authentication, metadata, true)
//                resized.let { Triple(it.id, it.contentType, it.contentLength) }
//            } else {
//                verifyHasPermission(authentication, metadata, false)
//                Triple(null, metadata.contentType, metadata.contentLength)
//            }
//        } else {
//            verifyHasPermission(authentication, metadata, false)
//            Triple(null, metadata.contentType, metadata.contentLength)
//        }
//        if (contentType?.startsWith("image/") != true) {
//            throw NoSuchElementException("Not Found")
//        }
//        val headers = HttpHeaders()
//        var filename = metadata.name
//        if (contentType.startsWith("image/") || contentType.startsWith("video/") || contentType.startsWith("audio/")) {
//            val parts = contentType.split("/")
//            filename = "$filename.${parts.lastOrNull() ?: ""}"
//        }
//        if (download == true) {
//            val disposition = "attachment; filename=\"$filename\""
//            headers["Content-Disposition"] = disposition
//        }
//        headers["Content-Length"] = (contentLength ?: error("missing content length")).toString()
//        headers["Content-Type"] = contentType
//        val resource = storage.downloadAsResource(metadata, supplementaryId)
//        return ResponseEntity(resource, headers, HttpStatus.OK)
//    }
//
//    @GetMapping("/image/{slug}")
//    suspend fun imageBySlug(
//        authentication: AuthenticationContext?,
//        @PathVariable slug: String?,
//        @RequestParam id: UUID?,
//        @RequestParam key: String?,
//        @RequestParam download: Boolean?
//    ): ResponseEntity<Resource> {
//        val metadata = when {
//            id != null -> {
//                val metadata = metadataService.getById(id) ?: throw NoSuchElementException("Not Found")
//                metadataPermissionEvaluator.isContentAllowed(
//                    authentication,
//                    metadata,
//                    PermissionAction.VIEW
//                )
//                metadata
//            }
//
//            slug != null -> {
//                val lastDot = slug.lastIndexOf('.')
//                val slug = slug.take(lastDot)
//                val metadataId = slugService.get(slug)?.metadataId ?: throw NoSuchElementException("Not Found")
//                val metadata = metadataService.getById(metadataId) ?: throw NoSuchElementException("Not Found")
//                metadataPermissionEvaluator.isContentAllowed(
//                    authentication,
//                    metadata,
//                    PermissionAction.VIEW
//                )
//                metadata
//            }
//
//            else -> throw NoSuchElementException("Not Found")
//        }
//        if (metadata.deleted && !groupEvaluator.hasAdminGroup(authentication)) {
//            throw NoSuchElementException("Not Found")
//        }
//        return getImage(authentication, metadata, key, download)
//    }
//
//    @GetMapping("/image")
//    suspend fun image(
//        authentication: AuthenticationContext?,
//        @RequestParam slug: String?,
//        @RequestParam id: UUID?,
//        @RequestParam key: String?,
//        @RequestParam download: Boolean?
//    ): ResponseEntity<Resource> = imageBySlug(authentication, slug, id, key, download)
//}