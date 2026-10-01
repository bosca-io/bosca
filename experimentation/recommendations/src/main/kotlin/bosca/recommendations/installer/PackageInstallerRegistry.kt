package bosca.recommendations.installer

import bosca.analytics.service.AnalyticsQueryService
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.experimentation.configuration.ExperimentationConfig
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.languages.service.LanguagesService
import bosca.pipelines.service.PipelineService
import bosca.profile.profile.service.ProfileService
import bosca.recommendations.service.RecommendationPlacementService
import bosca.recommendations.service.RecommendationStrategyService
import bosca.scheduler.service.SchedulerService
import bosca.security.service.SecurityService

/**
 * Registers the recommendation module's package installer into the DI container so that
 * the platform's installer subsystem can bootstrap recommendation strategies, placements,
 * and their associated analytics queries from bundled package definitions during initial
 * setup or package import.
 */
@Providers
class PackageInstallerRegistry {

    @Provider(name = "recommendations")
    fun recommendationsInstaller(
        analyticsQueryService: AnalyticsQueryService,
        strategyService: RecommendationStrategyService,
        placementService: RecommendationPlacementService,
        securityService: SecurityService,
        pipelineService: PipelineService,
        schedulerService: SchedulerService,
        languagesService: LanguagesService,
        experimentationConfig: ExperimentationConfig,
    ): PackageInstaller = RecommendationsInstaller(
        analyticsQueryService, strategyService, placementService, securityService, pipelineService, schedulerService,
        languagesService, experimentationConfig,
    )

    @Provider(name = "recommendations-tuning-attribute-type")
    fun tuningAttributeTypeInstaller(
        profileService: ProfileService,
    ): PackageInstaller = TuningAttributeTypeInstaller(profileService)

    @Provider(name = "recommendations-learned-interest-attribute-type")
    fun learnedInterestAttributeTypeInstaller(
        profileService: ProfileService,
    ): PackageInstaller = LearnedInterestAttributeTypeInstaller(profileService)

    /**
     * The recommendations installable package. Without this definition the [recommendationsInstaller]
     * above is registered but never invoked: the platform installer only runs installers named by a
     * [PackageInstallation] whose [PackageInstallation.key] is in the server's `packages` config. `key`
     * must equal the `packages` entry ("recommendations"); the version's installer names the provider above.
     */
    @Provider(name = "recommendations-package")
    fun recommendationsPackage(): PackageInstallation = PackageInstallation(
        key = "recommendations",
        name = "Recommendations",
        versions = listOf(
            PackageInstallationVersion(version = "1.0.0", installerNames = listOf("recommendations")),
            // 1.0.1 re-runs the (idempotent) recommendations installer so existing installations pick up
            // newly bundled seed data — here the `recommender-feedback` analytics query for the ML
            // feedback->retraining loop. The installer's own bumped version gates the re-run.
            PackageInstallationVersion(version = "1.0.1", installerNames = listOf("recommendations")),
            // 1.0.2 re-runs the installer so existing installations pick up new recommender analytics queries:
            // the `recommender-content-features` change that emits `metadata.labels` (chr(31)-joined), and the
            // new `recommender-content-embeddings` query over the metadata_embedding view. Level 1 gates each
            // package version permanently, so a new entry is required to re-invoke install(); the installer's
            // bumped version (1.0.7) clears the Level 2 per-installer gate.
            PackageInstallationVersion(version = "1.0.2", installerNames = listOf("recommendations")),
            // 1.0.3 forces another re-run: an existing install recorded 1.0.2 with installer 1.0.7 before the
            // `recommender-content-embeddings` query was bundled, so that query never got seeded (training
            // then fails with "Query not found: recommender-content-embeddings"). Both gates key on a version
            // — Level 1 on the package version, Level 2 on the installer version — so a new package version
            // AND the bumped installer version (1.0.8) are BOTH required to re-invoke the installer.
            PackageInstallationVersion(version = "1.0.3", installerNames = listOf("recommendations")),
            // 1.0.4 registers the `bosca.recommendations.tuning` profile attribute type — the
            // account-scoped store for feed clients' Boost/Lower/Hide tuning weights.
            PackageInstallationVersion(version = "1.0.4", installerNames = listOf("recommendations-tuning-attribute-type")),
            // 1.0.5 re-runs the recommendations installer so existing installations pick up the two new
            // write-time Personalization Signal compute pipelines, triggered on
            // ProfileAttributesAdded/Updated. Both gates key on a version — Level 1 on this package version,
            // Level 2 on the installer version — so a new package version AND the bumped installer version
            // (1.0.11) are BOTH required to re-invoke the installer.
            PackageInstallationVersion(version = "1.0.5", installerNames = listOf("recommendations")),
            // 1.0.6 re-runs the installer so existing installations pick up the new `recommender-user-signals`
            // analytics query — the configurable, signal-keyed replacement for the profile_type
            // `recommender-user-features`. New package version + bumped installer version (1.0.12) clear both gates.
            PackageInstallationVersion(version = "1.0.6", installerNames = listOf("recommendations")),
            // 1.0.7 re-runs the installer so existing installations pick up the "people like you" cohort
            // co-engagement: the `recommendations-cohort-co-engagement` analytics query + the
            // idempotently-seeded COHORT_CO_ENGAGEMENT strategy + its daily schedule. New package version +
            // bumped installer version (1.0.13) clear both gates.
            PackageInstallationVersion(version = "1.0.7", installerNames = listOf("recommendations")),
            // 1.0.8 registers the `bosca.recommendations.learned_interest` attribute type and re-runs the
            // recommendations installer to seed the learned-interest inference pipeline (Phase 4),
            // triggered on ProfileRatingAdded. The installer's bumped version (1.0.14) clears its per-installer gate.
            PackageInstallationVersion(
                version = "1.0.8",
                installerNames = listOf("recommendations", "recommendations-learned-interest-attribute-type"),
            ),
            // 1.0.9 re-runs the installer so existing installs pick up the tunable cohort co-engagement caps:
            // the query gains basketCap/sourceCap parameters and the strategy is seeded with the default
            // configuration. New package version + bumped installer version (1.0.15) clear both gates.
            PackageInstallationVersion(version = "1.0.9", installerNames = listOf("recommendations")),
            // 1.0.10 re-runs the recommendations installer so existing installations repair a missing
            // daily train-model schedule even when the PERSONALIZED strategy already exists. New package
            // version + bumped installer version (1.0.16) clear both installer gates.
            PackageInstallationVersion(version = "1.0.10", installerNames = listOf("recommendations")),
            // 1.0.11 repairs the sole Input-to-action edge in an exact, otherwise-untouched generated
            // recommendation graph when an older Studio save dropped it. New package version + bumped
            // installer version (1.0.17) clear both installer gates.
            PackageInstallationVersion(version = "1.0.11", installerNames = listOf("recommendations")),
            // 1.0.12 refreshes the recommender content-feature query so trained candidate indexes are
            // partitioned by the cached recommendation contexts assigned to each metadata item.
            PackageInstallationVersion(version = "1.0.12", installerNames = listOf("recommendations")),
            // 1.0.13 repairs partial strategy installations: it restores the TRENDING fallback when other
            // strategy types already exist and rebinds the bundled legacy co-engagement strategy to the
            // current query shape expected by the evaluator. Installer 1.0.19 clears the second gate.
            PackageInstallationVersion(version = "1.0.13", installerNames = listOf("recommendations")),
            // 1.0.15 refreshes training eligibility, installs the recommendation language-resolution
            // context, and makes the candidate language facet use resolved language tags. These changes
            // share one installer rerun so an upgrade cannot enqueue duplicate recompute/train jobs.
            PackageInstallationVersion(version = "1.0.15", installerNames = listOf("recommendations")),
            // 1.0.16 refreshes the event-backed queries from the deployment's warehouse table and adds
            // deterministic offset pagination to whole-crowd co-engagement. Installer 1.0.22 clears the
            // per-installer gate so existing deployments receive the updated definitions.
            PackageInstallationVersion(version = "1.0.16", installerNames = listOf("recommendations")),
            // 1.0.17 refreshes the content-embedding analytics query to expose ordered chunk vectors.
            // Installer 1.0.23 clears the per-installer gate for existing deployments.
            PackageInstallationVersion(version = "1.0.17", installerNames = listOf("recommendations")),
            // 1.0.18 canonicalizes event principal IDs to effective profile IDs in every behavioral
            // recommendation query, falling back to the newest active owned profile when no primary is set.
            // Installer 1.0.24 clears the per-installer gate so existing deployments refresh the saved queries.
            PackageInstallationVersion(version = "1.0.18", installerNames = listOf("recommendations")),
            // 1.0.19 refreshes cohort co-engagement for multi-membership profiles. The query now caps and
            // joins interactions within each cohort membership, preserving every categorical interest instead
            // of allowing activity to bleed across memberships. Installer 1.0.25 clears the second gate.
            PackageInstallationVersion(version = "1.0.19", installerNames = listOf("recommendations")),
            // 1.0.20 refreshes the personalized-training interaction query with the event timestamp used to
            // record each artifact's training-data cutoff. Installer 1.0.26 clears the per-installer gate.
            PackageInstallationVersion(version = "1.0.20", installerNames = listOf("recommendations")),
            // 1.0.21 adds the complete eligible-profile query so every profile is represented in the
            // personalized serving index. Installer 1.0.27 clears the per-installer gate.
            PackageInstallationVersion(version = "1.0.21", installerNames = listOf("recommendations")),
            // 1.0.22 restricts recommendation engagement inputs to interactions, completions, and page
            // impressions so passive list/item visibility does not influence recommendation behavior.
            // Installer 1.0.28 clears the per-installer gate and refreshes the saved analytics queries.
            PackageInstallationVersion(version = "1.0.22", installerNames = listOf("recommendations")),
            // 1.0.23 refreshes behavioral queries so scroll-depth milestones remain available as
            // view-quality telemetry without becoming additional recommendation engagements.
            // Installer 1.0.29 clears the per-installer gate.
            PackageInstallationVersion(version = "1.0.23", installerNames = listOf("recommendations")),
            // Refresh analytics inputs for attributed engagement, exposure, consumption, and feedback timestamps.
            PackageInstallationVersion(version = "1.0.24", installerNames = listOf("recommendations")),
            // Guide state supplies positive training evidence for both guides and their steps.
            PackageInstallationVersion(version = "1.0.25", installerNames = listOf("recommendations")),
            PackageInstallationVersion(version = "1.0.27", installerNames = listOf("recommendations")),
            PackageInstallationVersion(version = "1.0.28", installerNames = listOf("recommendations")),
            PackageInstallationVersion(version = "1.0.29", installerNames = listOf("recommendations")),
            // Refresh event-backed recommendation queries with the bot user-agent filter.
            // RecommendationsInstaller 1.0.36 clears the per-installer gate.
            PackageInstallationVersion(version = "1.0.30", installerNames = listOf("recommendations")),
        ),
    )
}
