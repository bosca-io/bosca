//package bosca.files
//
//import bosca.content.metadata.model.MetadataSupplementary
//import bosca.content.metadata.service.MetadataService
//import bosca.security.model.PermissionAction
//import bosca.security.service.GroupEvaluator
//import bosca.security.service.MetadataPermissionEvaluator
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
//import org.springframework.web.bind.annotation.GetMapping
//import org.springframework.web.bind.annotation.PostMapping
//import org.springframework.web.bind.annotation.RequestMapping
//import org.springframework.web.bind.annotation.RequestParam
//import org.springframework.web.bind.annotation.RequestPart
//import org.springframework.web.bind.annotation.RestController
//import java.util.*
//
//@RestController
//@RequestMapping("/files/metadata")
//class MetadataFilesController(
//    private val metadataService: MetadataService,
//    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
//    private val groupEvaluator: GroupEvaluator,
//    private val storage: ObjectStorageService,
//    private val signer: UrlSigner
//) {
//
//    private suspend fun getSupplementary(
//        supplementaryId: UUID?,
//    ): MetadataSupplementary? {
//        return if (supplementaryId != null) {
//            metadataService.getSupplementaryById(supplementaryId)
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
//        val (metadata, supplementary) = if (signer.verify(request.uri)) {
//            val metadata = metadataService.getById(id ?: error("missing id")) ?: throw SecurityException()
//            if (supplementaryId != null) {
//                val supplementary = metadataService.getSupplementaryById(supplementaryId) ?: return ResponseEntity.notFound().build()
//                metadata to supplementary
//            } else {
//                metadata to null
//            }
//        } else if (supplementaryId != null) {
//            val supplementary = metadataService.getSupplementaryById(supplementaryId) ?: return ResponseEntity.notFound().build()
//            val metadata = metadataService.getById(supplementary.metadataId) ?: return ResponseEntity.notFound().build()
//            metadataPermissionEvaluator.verifySupplementaryAllowed(authentication, metadata, PermissionAction.VIEW)
//            metadata to supplementary
//        } else {
//            val metadata = metadataService.getById(id ?: error("missing id")) ?: return ResponseEntity.notFound().build()
//            metadataPermissionEvaluator.verifyContentAllowed(authentication, metadata, PermissionAction.VIEW)
//            metadata to null
//        }
//
//        if (metadata.deleted && !groupEvaluator.hasAdminGroup(authentication)) {
//            return ResponseEntity.notFound().build()
//        }
//
//        val length = supplementary?.contentLength ?: metadata.contentLength ?: error("missing content length")
//        val range = request.headers.range.firstOrNull()?.let {
//            it.getRangeStart(length)..it.getRangeEnd(length)
//        }
//        val path = storage.getPath(metadata, supplementary?.id)
//        val stream = range?.let { storage.getInputStreamRange(path, it) } ?: storage.getInputStream(path)
//
//        val contentType = supplementary?.contentType ?: metadata.contentType.let {
//            if (it == "audio/mpeg" && metadata.name.endsWith(".mp3")) "audio/mp3" else it
//        }
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
//            val filename = metadata.name.let {
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
//        @RequestParam ready: Boolean?,
//        @RequestParam redirect: String?,
//        @RequestPart("file") file: FilePart,
//    ): ResponseEntity<String> {
//        val metadata = id?.let { metadataService.getById(it) } ?: return ResponseEntity.notFound().build()
//
//        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
//
//        val supplementary = getSupplementary(supplementaryId)
//
//        val contentType = file.headers().contentType?.toString() ?: "application/octet-stream"
//        val length = storage.upload(metadata, supplementary?.id, file)
//
//        if (supplementary != null) {
//            metadataService.setSupplementaryUploaded(supplementary.id ?: error("missing id"), contentType, length)
//        } else {
//            metadataService.setUploaded(id, contentType, length)
//            if (ready == true) {
//                metadataService.setReady(metadata)
//            }
//        }
//
//        return redirect?.let {
//            val headers = HttpHeaders()
//            headers.add(HttpHeaders.LOCATION, it)
//            ResponseEntity<String>("Redirect", headers, HttpStatus.FOUND)
//        } ?: ResponseEntity<String>("Upload successful", HttpStatus.CREATED)
//    }
//}