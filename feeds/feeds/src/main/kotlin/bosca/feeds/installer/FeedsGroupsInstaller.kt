package bosca.feeds.installer

import bosca.feeds.graphql.FEEDS_ADMINISTRATOR_GROUP
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.service.SecurityService
import org.slf4j.LoggerFactory

/**
 * Seeds the `feeds.administrator` security group at startup, idempotently. Managed feed-source admin
 * mutations + reads gate on this group via `GroupEvaluator.verifyFeedsAdmin(...)`, so it must EXIST
 * for those checks to be assignable. Assigning principals to it is an operational concern.
 */
class FeedsGroupsInstaller(
    private val securityService: SecurityService,
) : PackageInstaller {

    override val version: String = "1.0.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        if (securityService.getGroupByName(FEEDS_ADMINISTRATOR_GROUP, GroupType.SYSTEM) != null) {
            log.info("group '{}' already exists, skipping", FEEDS_ADMINISTRATOR_GROUP)
            return
        }
        log.info("creating group '{}'", FEEDS_ADMINISTRATOR_GROUP)
        securityService.addGroup(
            Group(
                name = FEEDS_ADMINISTRATOR_GROUP,
                description = "Feeds administrators",
                type = GroupType.SYSTEM,
            ),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(FeedsGroupsInstaller::class.java)
    }
}
