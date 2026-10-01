package bosca.localization.configuration

import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.localization.export.ExportEngine
import bosca.localization.repository.LocalizationMigration
import bosca.localization.security.LocalizationProjectPermissionEvaluator
import bosca.localization.service.LocalizationService
import bosca.localization.sync.CrowdinSyncProvider
import bosca.localization.sync.SyncProviders
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService

/**
 * DI providers exposed by the `localization` module. Covers the Flyway migration
 * descriptor, the [ExportEngine] (constructed with the full bundled exporter set),
 * the per-project permission evaluator, and the registered list of [SyncProvider]
 * implementations consumed by the sync service.
 */
@Providers
class ProviderConfiguration {

    @Provider(name = "localization-migrations")
    fun migration(): Migration = LocalizationMigration()

    @Provider
    fun exportEngine(): ExportEngine = ExportEngine()

    @Provider
    fun syncProviders(): SyncProviders = SyncProviders(listOf(CrowdinSyncProvider()))

    @Provider
    fun permissionEvaluator(
        service: LocalizationService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator
    ): LocalizationProjectPermissionEvaluator =
        LocalizationProjectPermissionEvaluator(service, securityService, groupEvaluator)
}
