package bosca.workops.service

import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.pipelines.service.PipelineSecretService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.store.pipelines.AppStorePublisher
import bosca.store.pipelines.PlayPublisher
import bosca.workops.deploy.AppStoreTargetConfig
import bosca.workops.deploy.DeployConfigService
import bosca.workops.deploy.DeployTargetKind
import bosca.workops.deploy.GooglePlayTargetConfig
import bosca.workops.model.release.ReleaseStoreCrashFeedback
import bosca.workops.model.release.ReleaseStoreObservation
import bosca.workops.model.release.ReleaseStoreState
import bosca.workops.model.release.ReleaseStoreTelemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** WorkOps release projection over the shared vendor adapters and analytics event history. */
@ServiceImplementation
class StoreTelemetryServiceImpl(
    private val releases: ReleaseService,
    private val versions: VersionService,
    private val environments: EnvironmentService,
    private val deployConfigs: DeployConfigService,
    private val secrets: PipelineSecretService,
    private val play: PlayPublisher,
    private val appStore: AppStorePublisher,
    private val analytics: AnalyticsQueryExecutionService,
) : StoreTelemetryService {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun forRelease(releaseId: UUID): ReleaseStoreTelemetry {
        val release = releases.getById(releaseId) ?: error("Release $releaseId not found")
        val programEnvironments = environments.listByProgram(release.programId)
        val states = mutableListOf<ReleaseStoreState>()
        for (bundled in releases.listVersions(releaseId)) {
            val version = versions.getById(bundled.versionId)
                ?: error("Release $releaseId references missing version ${bundled.versionId}")
            val refs = listOf("refs/tags/${version.name}", version.name, "refs/heads/main").distinct()
            for (environment in programEnvironments) {
                val declaration = deployConfigs.forEnvironment(bundled.projectId, environment.key, refs)
                    ?: deployConfigs.forEnvironment(bundled.projectId, environment.name, refs)
                    ?: continue
                for (target in declaration.effectiveTargets()) {
                    when (target.targetKind()) {
                        DeployTargetKind.GOOGLE_PLAY -> {
                            val cfg = json.decodeFromJsonElement(GooglePlayTargetConfig.serializer(), target.config)
                            val credential = secrets.resolve(cfg.serviceAccountSecret)
                                ?: error("pipeline secret '${cfg.serviceAccountSecret}' (the Play service account) is not set")
                            val state = play.trackState(credential, cfg.packageName, cfg.track)
                            states += ReleaseStoreState(
                                projectId = bundled.projectId,
                                versionId = bundled.versionId,
                                environmentId = environment.id,
                                environmentKey = environment.key,
                                store = STORE_PLAY,
                                applicationId = cfg.packageName,
                                appVersion = version.name,
                                buildNumber = state.versionCodes.joinToString(","),
                                track = state.track,
                                rolloutPercentage = state.userFraction * 100.0,
                                releaseState = state.status,
                            )
                        }
                        DeployTargetKind.APP_STORE -> {
                            val cfg = json.decodeFromJsonElement(AppStoreTargetConfig.serializer(), target.config)
                            val credential = secrets.resolve(cfg.ascKeySecret)
                                ?: error("pipeline secret '${cfg.ascKeySecret}' (the App Store Connect API key) is not set")
                            val state = appStore.state(credential, cfg.bundleId, version.name)
                            states += ReleaseStoreState(
                                projectId = bundled.projectId,
                                versionId = bundled.versionId,
                                environmentId = environment.id,
                                environmentKey = environment.key,
                                store = STORE_APP,
                                applicationId = cfg.bundleId,
                                appVersion = version.name,
                                buildNumber = state.buildNumber,
                                reviewState = state.reviewState,
                                betaReviewState = state.betaReviewState,
                                buildProcessingState = state.buildProcessingState,
                                phasedReleaseState = state.phasedReleaseState,
                                testFlightGroups = state.testFlightGroups,
                                testFlightCrashFeedback = state.testFlightCrashFeedback.map { feedback ->
                                    ReleaseStoreCrashFeedback(
                                        id = feedback.id,
                                        comment = feedback.comment,
                                        email = feedback.email,
                                        deviceModel = feedback.deviceModel,
                                        osVersion = feedback.osVersion,
                                        createdAt = feedback.createdAt,
                                    )
                                },
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }
        val distinctStates = states.distinctBy {
            listOf(it.projectId, it.environmentId, it.store, it.applicationId, it.appVersion)
        }
        return ReleaseStoreTelemetry(
            states = distinctStates,
            observations = observations(distinctStates),
        )
    }

    private suspend fun observations(states: List<ReleaseStoreState>): List<ReleaseStoreObservation> {
        val observations = mutableListOf<ReleaseStoreObservation>()
        for (identity in states.distinctBy { it.applicationId to it.appVersion }) {
            val response = analytics.execute(
                ANALYTICS_QUERY,
                listOf(
                    AnalyticsQueryExecutionParameterInput("application_id", JsonPrimitive(identity.applicationId)),
                    AnalyticsQueryExecutionParameterInput("app_version", JsonPrimitive(identity.appVersion)),
                ),
            )
            response.records.mapNotNullTo(observations, ::observation)
        }
        return observations.sortedByDescending { it.observedAt }
    }

    private fun observation(record: JsonElement): ReleaseStoreObservation? {
        val row = record as? JsonObject ?: return null
        val payload = when (val encoded = row["payload"]) {
            is JsonPrimitive -> encoded.contentOrNull?.let(json::parseToJsonElement)
            else -> encoded
        } as? JsonObject ?: return null
        val applicationId = payload.string("applicationId") ?: return null
        val appVersion = payload.string("appVersion") ?: return null
        val store = payload.string("store") ?: return null
        val telemetryType = row.string("telemetry_type") ?: return null
        val observedAt = row.string("created")?.let(OffsetDateTime::parse)
            ?: payload.string("observedAt")?.let(OffsetDateTime::parse)
            ?: return null
        return ReleaseStoreObservation(
            store = store,
            applicationId = applicationId,
            appVersion = appVersion,
            telemetryType = telemetryType,
            observedAt = observedAt,
            payload = payload,
        )
    }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private companion object {
        const val ANALYTICS_QUERY = "store-release-telemetry"
        const val STORE_PLAY = "GOOGLE_PLAY"
        const val STORE_APP = "APP_STORE"
    }
}
