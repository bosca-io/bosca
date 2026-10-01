package bosca.ecommerce.configuration

import bosca.content.metadata.service.MetadataPublishListener
import bosca.db.migrations.Migration
import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.ecommerce.graphql.CartAccessEvaluator
import bosca.ecommerce.installer.EcommerceGroupsInstaller
import bosca.ecommerce.installer.EcommerceScheduledJobsInstaller
import bosca.ecommerce.migration.EcommerceMigration
import bosca.ecommerce.repository.CartAddressRepository
import bosca.ecommerce.repository.PromotionRedemptionRepository
import bosca.ecommerce.repository.PromotionRepository
import bosca.ecommerce.service.CartPricer
import bosca.ecommerce.service.AccountService
import bosca.ecommerce.service.CustomerService
import bosca.ecommerce.service.DefaultTaxCalculator
import bosca.ecommerce.service.EcommerceMetadataPublishListener
import bosca.ecommerce.service.FixedRateShippingRateProvider
import bosca.ecommerce.service.PaymentProcessor
import bosca.ecommerce.service.ProductService
import bosca.ecommerce.service.PromotionPricer
import bosca.ecommerce.service.ShippingRateProvider
import bosca.ecommerce.service.TaxCalculator
import bosca.ecommerce.service.TaxPricer
import bosca.ecommerce.service.TestPaymentProcessor
import bosca.profile.profile.service.ProfileService
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.scheduler.service.SchedulerService
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import kotlinx.serialization.json.Json

/**
 * DI providers for the ecommerce implementation module.
 *
 * Both providers are **named**: an unnamed `@Provider` returning an interface would overwrite that
 * type's single registry slot (silently disabling another module's provider). Naming registers them
 * as distinct keyed providers the platform aggregates via `ProviderRegistry.findAll`.
 */
@Providers
class Configuration {

    @Provider(name = "ecommerce-migrations")
    fun migration(): Migration = EcommerceMigration()

    /** Reacts to content publishes by advancing the matching product's pinned content version. */
    @Provider(name = "ecommerce-metadata-publish-listener")
    fun metadataPublishListener(productService: ProductService): MetadataPublishListener =
        EcommerceMetadataPublishListener(productService)

    /** The promotions pricing step — discovered by the cart's reprice pipeline via `findAll(CartPricer)`. */
    @Provider(name = "ecommerce-promotion-pricer")
    fun promotionPricer(
        promotionRepository: PromotionRepository,
        promotionRedemptionRepository: PromotionRedemptionRepository,
    ): CartPricer = PromotionPricer(promotionRepository, promotionRedemptionRepository)

    /** Default tax calculator (per-state rates; empty until configured — a real provider replaces it). */
    @Provider(singleton = true)
    fun taxCalculator(): TaxCalculator = DefaultTaxCalculator(emptyMap())

    /** The tax pricing step (runs after promotions; reads the cart's shipping address). */
    @Provider(name = "ecommerce-tax-pricer")
    fun taxPricer(
        taxCalculator: TaxCalculator,
        cartAddressRepository: CartAddressRepository,
    ): CartPricer = TaxPricer(taxCalculator, cartAddressRepository)

    /** Fixed-rate shipping provider. Registered under its providerKey so it resolves by name. */
    @Provider(name = "fixed-rate")
    fun fixedRateShippingRateProvider(): ShippingRateProvider = FixedRateShippingRateProvider()

    /** Test payment processor (always approves). Registered under its providerKey so it resolves by name. */
    @Provider(name = "test")
    fun testPaymentProcessor(): PaymentProcessor = TestPaymentProcessor()

    /** Authorizes cart access (owner via customer -> profile, or ecom administrator). */
    @Provider(singleton = true)
    fun cartAccessEvaluator(
        profileService: ProfileService,
        customerService: CustomerService,
        accountService: AccountService,
        groups: GroupEvaluator,
    ): CartAccessEvaluator = CartAccessEvaluator(profileService, customerService, accountService, groups)

    /** The ecom job queue (`@JobDefinition(queue = JobQueueNames.ecomJobQueue)` resolves to this). */
    @Provider(singleton = true, name = JobQueueNames.ecomJobQueue)
    fun ecomJobQueue(factory: JobQueueFactory): JobQueue = factory.create(JobQueueNames.ecomQueue)

    /** The runner that drains the ecom queue (enabled via `runners.enabled` on bosca-runner). */
    @Provider(singleton = true, name = JobQueueNames.ecomRunner)
    fun ecomJobQueueRunner(
        @ProviderName(JobQueueNames.ecomJobQueue)
        queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(queue, 100, distributedLockFactory, errorCapture)

    /** Registers the ecom periodic jobs (cart-expiration sweep) as cron-scheduled jobs. */
    @Provider(name = "ecommerce-scheduled-jobs")
    fun ecommerceScheduledJobsInstaller(
        schedulerService: SchedulerService,
        json: Json,
    ): PackageInstaller = EcommerceScheduledJobsInstaller(schedulerService, json)

    /** Seeds the `ecom.administrator` security group the admin gates depend on. */
    @Provider(name = "ecommerce-groups")
    fun ecommerceGroupsInstaller(
        securityService: SecurityService,
    ): PackageInstaller = EcommerceGroupsInstaller(securityService)

    /** The ecommerce installable package: seeds the admin group and installs the scheduled jobs at startup. */
    @Provider(name = "ecommerce")
    fun ecommercePackage(): PackageInstallation = PackageInstallation(
        key = "ecommerce",
        name = "Ecommerce",
        versions = listOf(
            PackageInstallationVersion(
                version = "1.0.0",
                installerNames = listOf("ecommerce-groups", "ecommerce-scheduled-jobs"),
            ),
            // Re-runs the scheduled-jobs installer to register the inventory-sync sweep;
            // the installer's own version was bumped to 1.1.0 to match (both gates are required).
            PackageInstallationVersion(
                version = "1.1.0",
                installerNames = listOf("ecommerce-scheduled-jobs"),
            ),
        ),
    )
}
