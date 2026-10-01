package bosca.ecommerce.installer

import bosca.ecommerce.graphql.ECOM_ADMINISTRATOR_GROUP
import bosca.ecommerce.jobs.CartExpirationSweepExecutor
import bosca.ecommerce.jobs.InventorySyncSweepExecutor
import bosca.ecommerce.jobs.ShipmentTrackingSweepExecutor
import bosca.ecommerce.jobs.SubscriptionRenewalExecutor
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.service.SchedulerService
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.service.SecurityService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/** Startup installers: the admin-group seed and the cron-job registration, both idempotent. */
class EcommerceInstallersTest {

    private val pkg = mockk<PackageInstallation>(relaxed = true)
    private val ver = mockk<PackageInstallationVersion>(relaxed = true)

    @Test
    fun `groups installer seeds the admin group when it is absent`() = runTest {
        val security = mockk<SecurityService>(relaxed = true)
        coEvery { security.getGroupByName(ECOM_ADMINISTRATOR_GROUP, GroupType.SYSTEM) } returns null

        EcommerceGroupsInstaller(security).install(pkg, ver)

        coVerify(exactly = 1) { security.addGroup(any()) }
    }

    @Test
    fun `groups installer skips when the admin group already exists`() = runTest {
        val security = mockk<SecurityService>(relaxed = true)
        coEvery { security.getGroupByName(ECOM_ADMINISTRATOR_GROUP, GroupType.SYSTEM) } returns
            Group(name = ECOM_ADMINISTRATOR_GROUP, description = "Ecommerce administrators", type = GroupType.SYSTEM)

        EcommerceGroupsInstaller(security).install(pkg, ver)

        coVerify(exactly = 0) { security.addGroup(any()) }
    }

    @Test
    fun `scheduled-jobs installer registers all jobs when none exist`() = runTest {
        val scheduler = mockk<SchedulerService>(relaxed = true)
        coEvery { scheduler.getJobs() } returns emptyList()

        EcommerceScheduledJobsInstaller(scheduler, Json).install(pkg, ver)

        coVerify(exactly = 4) { scheduler.createJob(any(), any()) }
    }

    @Test
    fun `scheduled-jobs installer registers only the new job when the rest already exist`() = runTest {
        val scheduler = mockk<SchedulerService>(relaxed = true)
        // The inventory-sync sweep was added later: on an already-installed system, re-running the
        // installer (gated by the bumped version) registers only the new job, leaving the rest untouched.
        val existing = listOf(CartExpirationSweepExecutor.NAME, SubscriptionRenewalExecutor.NAME, ShipmentTrackingSweepExecutor.NAME)
            .map { name -> mockk<ScheduledJob> { every { jobName } returns name } }
        coEvery { scheduler.getJobs() } returns existing

        EcommerceScheduledJobsInstaller(scheduler, Json).install(pkg, ver)

        coVerify(exactly = 1) { scheduler.createJob(any(), any()) }
    }

    @Test
    fun `scheduled-jobs installer skips all jobs when every one already exists`() = runTest {
        val scheduler = mockk<SchedulerService>(relaxed = true)
        val existing = listOf(
            CartExpirationSweepExecutor.NAME, SubscriptionRenewalExecutor.NAME,
            ShipmentTrackingSweepExecutor.NAME, InventorySyncSweepExecutor.NAME,
        ).map { name -> mockk<ScheduledJob> { every { jobName } returns name } }
        coEvery { scheduler.getJobs() } returns existing

        EcommerceScheduledJobsInstaller(scheduler, Json).install(pkg, ver)

        coVerify(exactly = 0) { scheduler.createJob(any(), any()) }
    }
}
