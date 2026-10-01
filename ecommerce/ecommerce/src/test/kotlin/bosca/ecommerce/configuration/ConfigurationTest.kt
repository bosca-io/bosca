@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.configuration

import bosca.content.metadata.service.MetadataPublishListener
import bosca.db.migrations.Migration
import bosca.ecommerce.graphql.CartAccessEvaluator
import bosca.ecommerce.repository.CartAddressRepository
import bosca.ecommerce.repository.PromotionRedemptionRepository
import bosca.ecommerce.repository.PromotionRepository
import bosca.ecommerce.service.CartPricer
import bosca.ecommerce.service.CustomerService
import bosca.ecommerce.service.PaymentProcessor
import bosca.ecommerce.service.ProductService
import bosca.ecommerce.service.ShippingRateProvider
import bosca.ecommerce.service.TaxCalculator
import bosca.installer.model.PackageInstallation
import bosca.installer.service.PackageInstaller
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.profile.profile.service.ProfileService
import bosca.scheduler.service.SchedulerService
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** The module's DI provider factory: every @Provider builds its component from the given dependencies. */
class ConfigurationTest {

    private val config = Configuration()

    @Test
    fun `simple providers build their components`() {
        assertTrue(config.migration() is Migration)
        assertTrue(config.metadataPublishListener(mockk()) is MetadataPublishListener)
        assertTrue(config.promotionPricer(mockk<PromotionRepository>(), mockk<PromotionRedemptionRepository>()) is CartPricer)
        assertNotNull(config.taxCalculator())
        assertTrue(config.taxPricer(mockk<TaxCalculator>(), mockk<CartAddressRepository>()) is CartPricer)
        assertTrue(config.fixedRateShippingRateProvider() is ShippingRateProvider)
        assertTrue(config.testPaymentProcessor() is PaymentProcessor)
    }

    @Test
    fun `cart access evaluator is constructed from its collaborators`() {
        val evaluator = config.cartAccessEvaluator(mockk<ProfileService>(), mockk<CustomerService>(), mockk<bosca.ecommerce.service.AccountService>(), mockk<GroupEvaluator>())
        assertTrue(evaluator is CartAccessEvaluator)
    }

    @Test
    fun `job queue and runner are built from the queue factory`() {
        val factory = mockk<JobQueueFactory>()
        every { factory.create(JobQueueNames.ecomQueue) } returns mockk<JobQueue>()
        val queue = config.ecomJobQueue(factory)
        assertNotNull(queue)

        val runner = config.ecomJobQueueRunner(mockk<JobQueue>(), mockk<DistributedLockFactory>(), mockk<bosca.di.ObjectProvider<ErrorCapture>>())
        assertTrue(runner is JobRunner)
    }

    @Test
    fun `installer providers and the package descriptor are built`() {
        assertTrue(config.ecommerceScheduledJobsInstaller(mockk<SchedulerService>(), Json) is PackageInstaller)
        assertTrue(config.ecommerceGroupsInstaller(mockk<SecurityService>()) is PackageInstaller)
        assertTrue(config.ecommercePackage() is PackageInstallation)
    }
}
