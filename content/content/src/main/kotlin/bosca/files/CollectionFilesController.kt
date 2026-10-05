//package bosca.files
//
//import bosca.content.collection.model.CollectionSupplementary
//import bosca.content.collection.service.CollectionService
//import bosca.security.model.PermissionAction
//import bosca.security.service.CollectionPermissionEvaluator
//import bosca.security.service.GroupEvaluator
//import bosca.storage.ObjectStorageService
//import bosca.storage.upload
//import bosca.url.UrlSigner
//import org.springframework.core.io.InputStreamResource
//import org.springframework.core.io.Resource
//import org.springframework.http.HttpHeaders
//import org.springframework.http.HttpStatus
//import org.springframework.http.ResponseEntity
//import org.springframework.http.codec.multipart.FilePart
//import org.springframework.http.server.reactive.ServerHttpRequest
//
//import org.springframework.web.bind.annotation.*
//import java.util.*
//
//@RestController
//@RequestMapping("/files/collection")
//class CollectionFilesController(
//    private val collectionService: CollectionService,
//    private val collectionPermissionEvaluator: CollectionPermissionEvaluator,
//    private val groupEvaluator: GroupEvaluator,
//    private val storage: ObjectStorageService,
//    private val signer: UrlSigner
//) {
//
//    private suspend fun getSupplementary(
//        supplementaryId: UUID?,
//    ): CollectionSupplementary? {
//        return if (supplementaryId != null) {
//            collectionService.getSupplementaryById(supplementaryId)
//        } else {
//            null
//        }
//    }
//
//    @GetMapping("/download", produces = ["*/*"])
//    suspend fun download(
//        authentication: AuthenticationContext?,
//        request: ServerHttpRequest,
//        @RequestParam id: UUID? = null,
//        @RequestParam("supplementary_id") supplementaryId: UUID? = null,
//        @RequestParam download: Boolean? = null,
//    ): ResponseEntity<Resource> {
//        val (collection, supplementary) = if (signer.verify(request.uri)) {
//            val collection = collectionService.getById(id ?: error("missing id")) ?: throw SecurityException()
//            if (supplementaryId != null) {
//                val supplementary =
//                    collectionService.getSupplementaryById(supplementaryId) ?: return ResponseEntity.notFound().build()
//                collection to supplementary
//            } else {
//                collection to null
//            }
//        } else if (supplementaryId != null) {
//            val supplementary =
//                collectionService.getSupplementaryById(supplementaryId) ?: return ResponseEntity.notFound().build()
//            val collection = collectionService.getById(supplementary.collectionId) ?: return ResponseEntity.notFound().build()
//            collectionPermissionEvaluator.verifySupplementaryAllowed(authentication, collection, PermissionAction.VIEW)
//            collection to supplementary
//        } else {
//            error("unsupported")
//        }
//
//        if (collection.deleted && !groupEvaluator.hasAdminGroup(authentication)) {
//            return ResponseEntity.notFound().build()
//        }
//
//        val length = supplementary?.contentLength ?: error("missing content length")
//        val range = request.headers.range.firstOrNull()?.let {
//            it.getRangeStart(length)..it.getRangeEnd(length)
//        }
//        val path = storage.getPath(collection, supplementary.id)
//        val stream = range?.let { storage.getInputStreamRange(path, it) } ?: storage.getInputStream(path)
//
//        val contentType = supplementary.contentType.let {
//            if (it == "audio/mpeg" && supplementary.name.endsWith(".mp3")) "audio/mp3" else it
//        } ?: "application/octet-stream"
//
//        val headers = HttpHeaders()
//        headers.add(HttpHeaders.CONTENT_TYPE, contentType)
//        headers.add(HttpHeaders.ACCEPT_RANGES, "bytes")
//
//        range?.let {
//            headers.add(HttpHeaders.CONTENT_RANGE, "bytes ${it.first}-${it.last}/${length}")
//        }
//
//        if (download == true) {
//            val filename = supplementary.name.let {
//                if (contentType.startsWith("image/") ||
//                    contentType.startsWith("video/") ||
//                    contentType.startsWith("audio/")
//                ) "$it.${contentType.split("/").lastOrNull()}" else it
//            }
//            headers.add("Content-Disposition", "attachment; filename=\"$filename\"")
//        }
//
//        return ResponseEntity<Resource>(InputStreamResource(stream), headers, HttpStatus.OK)
//    }
//
//    @PostMapping("/upload", consumes = ["*/*"])
//    suspend fun upload(
//        authentication: AuthenticationContext?,
//        @RequestParam id: UUID?,
//        @RequestParam("supplementary_id") supplementaryId: UUID?,
//        @RequestParam redirect: String?,
//        @RequestPart("file") file: FilePart,
//    ): ResponseEntity<String> {
//        val collection = id?.let { collectionService.getById(it) } ?: return ResponseEntity.notFound().build()
//
//        collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
//
//        val supplementary = getSupplementary(supplementaryId)
//        val contentType = file.headers().contentType?.toString() ?: "application/octet-stream"
//        val length = storage.upload(collection, supplementary?.id, file)
//
//        if (supplementary != null) {
//            collectionService.setSupplementaryUploaded(supplementary.id ?: error("missing id"), contentType, length)
//        } else {
//            error("unsupported")
//        }
//
//        return redirect?.let {
//            val headers = HttpHeaders()
//            headers.add(HttpHeaders.LOCATION, it)
//            ResponseEntity<String>("Redirect", headers, HttpStatus.FOUND)
//        } ?: ResponseEntity<String>("Upload successful", HttpStatus.CREATED)
//    }
//}