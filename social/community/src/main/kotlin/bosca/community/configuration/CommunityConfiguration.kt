package bosca.community.configuration

import bosca.community.repository.CommunityMigration
import bosca.community.repository.CommunityGroupRepository
import bosca.community.service.CommunityProfileCleanupHandler
import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.profile.profile.service.ProfileCleanupHandler
import bosca.security.service.SecurityService

@Providers
class CommunityConfiguration {

    @Provider(name = "community-migrations")
    fun migration(): Migration = CommunityMigration()

    /** Removes community memberships and groups after a profile permanently loses its identity. */
    @Provider(name = "community-profile-cleanup-handler")
    fun communityProfileCleanupHandler(
        communityGroupRepository: CommunityGroupRepository,
        securityService: SecurityService,
    ): ProfileCleanupHandler = CommunityProfileCleanupHandler(communityGroupRepository, securityService)
}
