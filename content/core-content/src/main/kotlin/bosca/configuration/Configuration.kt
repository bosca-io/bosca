package bosca.configuration

import bosca.content.collection.service.CollectionService
import bosca.content.image.model.ImageResizerConfiguration
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.server.BoscaApplication

@Providers
class Configuration {

    @Provider(singleton = true)
    fun metadataPermissionEvaluator(
        service: MetadataService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator
    ) = MetadataPermissionEvaluator(service, securityService, groupEvaluator)

    @Provider(singleton = true)
    fun collectionPermissionEvaluator(
        service: CollectionService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator
    ) = CollectionPermissionEvaluator(service, securityService, groupEvaluator)

    @Provider(singleton = true)
    fun imageResizer(application: BoscaApplication): ImageResizerConfiguration =
        application.environment.config.property("content.images.resizer").getAs()
}