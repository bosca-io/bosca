package bosca.artifacts.configuration

import bosca.artifacts.repository.ArtifactsMigration
import bosca.artifacts.service.ArtifactNamespacePermissionEvaluator
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService

/**
 * DI provider configuration for the artifacts module, registering the database
 * migration and the permission evaluator. Service implementations are registered
 * automatically via `@ServiceImplementation` on the impl classes.
 */
@Providers
class Configuration {

    @Provider(name = "artifacts-migrations", singleton = true)
    fun artifactsMigration(): Migration = ArtifactsMigration()

    @Provider(singleton = true)
    fun artifactNamespacePermissionEvaluator(
        repositoryService: ArtifactRepositoryService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator,
    ): ArtifactNamespacePermissionEvaluator =
        ArtifactNamespacePermissionEvaluator(repositoryService, securityService, groupEvaluator)

    @Provider(singleton = true)
    fun artifactPermissionEvaluator(
        namespaceEvaluator: ArtifactNamespacePermissionEvaluator,
    ): ArtifactPermissionEvaluator = ArtifactPermissionEvaluator(namespaceEvaluator)
}
