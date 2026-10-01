package bosca.content.metadata.routes

import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.routes.annotations.RouteController
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.UrlSigner

/** Serves file links through the same permission checks and streaming behavior as API downloads. */
@RouteController("/content/file")
class DownloadFile(
    metadataService: MetadataService,
    metadataPermissionEvaluator: MetadataPermissionEvaluator,
    objectService: ObjectStorageService,
    urlSigner: UrlSigner,
) : Download(metadataService, metadataPermissionEvaluator, objectService, urlSigner)
