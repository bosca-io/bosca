package bosca.ecommerce.installer

import bosca.ecommerce.graphql.ECOM_ADMINISTRATOR_GROUP
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.service.SecurityService
import org.slf4j.LoggerFactory

/**
 * Seeds the `ecom.administrator` security group at startup, idempotently (only creates it when a
 * SYSTEM group of that name does not already exist). Every non-Metadata ecom admin mutation and
 * PII/admin read gates on this group via `GroupEvaluator.verifyHasGroup(...)`, so the group must
 * EXIST for those checks to be assignable. Assigning principals to it is an operational concern, not
 * part of seeding.
 */
class EcommerceGroupsInstaller(
    private val securityService: SecurityService,
) : PackageInstaller {

    override val version: String = "1.0.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        if (securityService.getGroupByName(ECOM_ADMINISTRATOR_GROUP, GroupType.SYSTEM) != null) {
            log.info("group '{}' already exists, skipping", ECOM_ADMINISTRATOR_GROUP)
            return
        }
        log.info("creating group '{}'", ECOM_ADMINISTRATOR_GROUP)
        securityService.addGroup(
            Group(
                name = ECOM_ADMINISTRATOR_GROUP,
                description = "Ecommerce administrators",
                type = GroupType.SYSTEM,
            ),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(EcommerceGroupsInstaller::class.java)
    }
}
