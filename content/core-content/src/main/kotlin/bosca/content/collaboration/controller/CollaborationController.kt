package bosca.content.collaboration.controller

//
//@RestController
//@RequestMapping("/api/v1/documents/collaboration")
//class CollaborationController(
//    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
//    private val service: MetadataService,
//    private val documentService: DocumentService,
//) {
//
//    @GetMapping("")
//    suspend fun getDocument(
//        authentication: AuthenticationContext,
//        id: UUID,
//        version: Int,
//    ): ByteArray? {
//        val metadata = service.getById(id, version) ?: return null
//        metadataPermissionEvaluator.verifyContentAllowed(authentication, metadata, PermissionAction.EDIT)
//        return documentService.getCollaboration(id, version)?.content
//    }
//
//    @PutMapping("")
//    suspend fun setDocument(
//        authentication: AuthenticationContext,
//        @RequestBody body: ByteArray,
//        id: UUID,
//        version: Int,
//    ): HttpStatus {
//        val metadata = service.getById(id, version) ?: return HttpStatus.NOT_FOUND
//        metadataPermissionEvaluator.verifyContentAllowed(authentication, metadata, PermissionAction.EDIT)
//        documentService.setCollaboration(DocumentCollaborationInput(id, version, body))
//        return HttpStatus.ACCEPTED
//    }
//}