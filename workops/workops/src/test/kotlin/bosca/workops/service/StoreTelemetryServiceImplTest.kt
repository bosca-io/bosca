package bosca.workops.service

import bosca.analytics.model.AnalyticsQueryResponse
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.pipelines.service.PipelineSecretService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.store.pipelines.AppStorePublisher
import bosca.store.pipelines.AppStoreCrashFeedbackResult
import bosca.store.pipelines.AppStoreStateResult
import bosca.store.pipelines.PlayPublisher
import bosca.store.pipelines.PlayTrackStateResult
import bosca.workops.deploy.AppStoreTargetConfig
import bosca.workops.deploy.DeployConfigService
import bosca.workops.deploy.DeployTargetEntry
import bosca.workops.deploy.EnvironmentDeployConfig
import bosca.workops.deploy.GooglePlayTargetConfig
import bosca.workops.model.environment.Environment
import bosca.workops.model.release.Release
import bosca.workops.model.release.ReleaseProjectVersion
import bosca.workops.model.version.Version
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StoreTelemetryServiceImplTest {
    private val releases = mockk<ReleaseService>()
    private val versions = mockk<VersionService>()
    private val environments = mockk<EnvironmentService>()
    private val configs = mockk<DeployConfigService>()
    private val secrets = mockk<PipelineSecretService>()
    private val play = mockk<PlayPublisher>()
    private val appStore = mockk<AppStorePublisher>()
    private val analytics = mockk<AnalyticsQueryExecutionService>()
    private val service = StoreTelemetryServiceImpl(
        releases, versions, environments, configs, secrets, play, appStore, analytics,
    )
    private val json = Json

    @Test
    fun `projects live vendor state and release-dimensional analytics observations`() = runTest {
        val releaseId = UUID.random()
        val programId = UUID.random()
        val projectId = UUID.random()
        val versionId = UUID.random()
        val environmentId = UUID.random()
        val release = Release(id = releaseId, programId = programId, name = "Summer")
        val version = Version(id = versionId, projectId = projectId, name = "1.4.0", sequenceNumber = 1)
        val environment = Environment(id = environmentId, programId = programId, key = "production", name = "Production")
        val declaration = EnvironmentDeployConfig(
            targets = listOf(
                DeployTargetEntry(
                    target = "google_play",
                    config = json.encodeToJsonElement(
                        GooglePlayTargetConfig.serializer(),
                        GooglePlayTargetConfig("io.example.app", track = "production", serviceAccountSecret = "play-sa"),
                    ),
                ),
                DeployTargetEntry(
                    target = "app_store",
                    config = json.encodeToJsonElement(
                        AppStoreTargetConfig.serializer(),
                        AppStoreTargetConfig("io.example.app", ascKeySecret = "asc-key"),
                    ),
                ),
            ),
        )
        coEvery { releases.getById(releaseId) } returns release
        coEvery { releases.listVersions(releaseId) } returns listOf(ReleaseProjectVersion(releaseId, projectId, versionId))
        coEvery { versions.getById(versionId) } returns version
        coEvery { environments.listByProgram(programId) } returns listOf(environment)
        coEvery { configs.forEnvironment(projectId, "production", any()) } returns declaration
        coEvery { secrets.resolve("play-sa") } returns "play-credential"
        coEvery { secrets.resolve("asc-key") } returns "asc-credential"
        coEvery { play.trackState("play-credential", "io.example.app", "production", null) } returns
            PlayTrackStateResult("production", listOf(42), "inProgress", 0.25)
        coEvery { appStore.state("asc-credential", "io.example.app", "1.4.0", null) } returns
            AppStoreStateResult(
                "1.4.0", "1.0.41", "WAITING_FOR_REVIEW", "APPROVED", "VALID", "ACTIVE", listOf("Internal"),
                listOf(AppStoreCrashFeedbackResult("feedback-1", "Crash", null, "iPhone17,1", "19.0", null)),
            )
        coEvery { analytics.execute("store-release-telemetry", any()) } answers {
            val parameters = secondArg<List<bosca.analytics.model.AnalyticsQueryExecutionParameterInput>>()
            val applicationId = (parameters.first { it.parameter == "application_id" }.value as JsonPrimitive).content
            AnalyticsQueryResponse(
                listOf(
                    buildJsonObject {
                        put("created", "2026-07-22T12:00:00Z")
                        put("telemetry_type", if (applicationId == "io.example.app") "store.vitals" else "store.state")
                        put("payload", buildJsonObject {
                            put("applicationId", applicationId)
                            put("appVersion", "1.4.0")
                            put("store", "GOOGLE_PLAY")
                            put("crashRate", 0.4)
                        }.toString())
                    },
                ),
            )
        }

        val telemetry = service.forRelease(releaseId)

        assertEquals(2, telemetry.states.size)
        val playState = telemetry.states.first { it.store == "GOOGLE_PLAY" }
        assertEquals(25.0, playState.rolloutPercentage)
        assertEquals("42", playState.buildNumber)
        val appState = telemetry.states.first { it.store == "APP_STORE" }
        assertEquals("ACTIVE", appState.phasedReleaseState)
        assertEquals(listOf("Internal"), appState.testFlightGroups)
        assertEquals("Crash", appState.testFlightCrashFeedback.single().comment)
        assertEquals(1, telemetry.observations.size)
        assertEquals(OffsetDateTime.parse("2026-07-22T12:00:00Z"), telemetry.observations.single().observedAt)
        assertEquals(0.4, (telemetry.observations.single().payload as JsonObject).getValue("crashRate").toString().toDouble())
        coVerify(exactly = 1) { analytics.execute("store-release-telemetry", any()) }
    }

    @Test
    fun `skips non-store targets and falls back from environment key to display name`() = runTest {
        val releaseId = UUID.random()
        val programId = UUID.random()
        val projectId = UUID.random()
        val versionId = UUID.random()
        coEvery { releases.getById(releaseId) } returns Release(id = releaseId, programId = programId, name = "R")
        coEvery { releases.listVersions(releaseId) } returns listOf(ReleaseProjectVersion(releaseId, projectId, versionId))
        coEvery { versions.getById(versionId) } returns Version(id = versionId, projectId = projectId, name = "1", sequenceNumber = 1)
        coEvery { environments.listByProgram(programId) } returns listOf(
            Environment(programId = programId, key = "prod", name = "Production"),
            Environment(programId = programId, key = "missing", name = "Missing"),
        )
        coEvery { configs.forEnvironment(projectId, "prod", any()) } returns null
        coEvery { configs.forEnvironment(projectId, "Production", any()) } returns EnvironmentDeployConfig(
            targets = listOf(
                DeployTargetEntry(target = "helm"),
                DeployTargetEntry(target = "unknown"),
            ),
        )
        coEvery { configs.forEnvironment(projectId, "missing", any()) } returns null
        coEvery { configs.forEnvironment(projectId, "Missing", any()) } returns null

        val telemetry = service.forRelease(releaseId)

        assertTrue(telemetry.states.isEmpty())
        assertTrue(telemetry.observations.isEmpty())
        coVerify(exactly = 0) { analytics.execute(any<String>(), any()) }
    }

    @Test
    fun `fails loudly for missing release version and credentials`() = runTest {
        val missing = UUID.random()
        coEvery { releases.getById(missing) } returns null
        assertTrue("not found" in assertFailsWith<IllegalStateException> { service.forRelease(missing) }.message.orEmpty())

        val releaseId = UUID.random()
        val programId = UUID.random()
        val projectId = UUID.random()
        val versionId = UUID.random()
        coEvery { releases.getById(releaseId) } returns Release(id = releaseId, programId = programId, name = "R")
        coEvery { releases.listVersions(releaseId) } returns listOf(ReleaseProjectVersion(releaseId, projectId, versionId))
        coEvery { environments.listByProgram(programId) } returns emptyList()
        coEvery { versions.getById(versionId) } returns null
        assertTrue("missing version" in assertFailsWith<IllegalStateException> { service.forRelease(releaseId) }.message.orEmpty())
    }

    @Test
    fun `analytics projection rejects malformed rows and falls back to payload observation time`() = runTest {
        val releaseId = UUID.random()
        val programId = UUID.random()
        val projectId = UUID.random()
        val versionId = UUID.random()
        val environment = Environment(id = UUID.random(), programId = programId, key = "prod", name = "Production")
        val version = Version(id = versionId, projectId = projectId, name = "2.0.0", sequenceNumber = 2)
        val declaration = EnvironmentDeployConfig(
            targets = listOf(
                DeployTargetEntry(
                    target = "google_play",
                    config = json.encodeToJsonElement(
                        GooglePlayTargetConfig.serializer(),
                        GooglePlayTargetConfig("io.example.two", track = "beta", serviceAccountSecret = "play"),
                    ),
                ),
            ),
        )
        coEvery { releases.getById(releaseId) } returns Release(id = releaseId, programId = programId, name = "R")
        coEvery { releases.listVersions(releaseId) } returns listOf(ReleaseProjectVersion(releaseId, projectId, versionId))
        coEvery { versions.getById(versionId) } returns version
        coEvery { environments.listByProgram(programId) } returns listOf(environment)
        coEvery { configs.forEnvironment(projectId, "prod", any()) } returns declaration
        coEvery { secrets.resolve("play") } returns "credential"
        coEvery { play.trackState("credential", "io.example.two", "beta", null) } returns
            PlayTrackStateResult("beta", listOf(7), "completed", 1.0)

        val completePayload = buildJsonObject {
            put("applicationId", "io.example.two")
            put("appVersion", "2.0.0")
            put("store", "GOOGLE_PLAY")
        }
        coEvery { analytics.execute("store-release-telemetry", any()) } returns AnalyticsQueryResponse(
            listOf(
                JsonPrimitive("not-an-object"),
                buildJsonObject { put("payload", JsonNull) },
                buildJsonObject { put("payload", JsonPrimitive("\"scalar\"")) },
                buildJsonObject { put("payload", buildJsonObject { put("appVersion", "2.0.0"); put("store", "GOOGLE_PLAY") }) },
                buildJsonObject { put("payload", buildJsonObject { put("applicationId", "io.example.two"); put("store", "GOOGLE_PLAY") }) },
                buildJsonObject { put("payload", buildJsonObject { put("applicationId", "io.example.two"); put("appVersion", "2.0.0") }) },
                buildJsonObject { put("payload", completePayload) },
                buildJsonObject {
                    put("telemetry_type", "store.vitals")
                    put("payload", completePayload)
                },
                buildJsonObject {
                    put("telemetry_type", "store.vitals")
                    put("payload", buildJsonObject {
                        put("applicationId", "io.example.two")
                        put("appVersion", "2.0.0")
                        put("store", "GOOGLE_PLAY")
                        put("observedAt", "2026-08-01T10:00:00Z")
                    })
                },
                buildJsonObject {
                    put("created", "2026-08-02T10:00:00Z")
                    put("telemetry_type", " ")
                    put("payload", completePayload)
                },
                buildJsonObject {
                    put("created", "2026-08-03T10:00:00Z")
                    put("telemetry_type", "store.state")
                    put("payload", buildJsonObject {
                        put("applicationId", buildJsonObject { })
                        put("appVersion", "2.0.0")
                        put("store", "GOOGLE_PLAY")
                    })
                },
                buildJsonObject {
                    put("created", buildJsonObject { })
                    put("telemetry_type", "store.state")
                    put("payload", buildJsonObject {
                        put("applicationId", JsonNull)
                        put("appVersion", "2.0.0")
                        put("store", "GOOGLE_PLAY")
                        put("observedAt", "2026-08-04T10:00:00Z")
                    })
                },
            ),
        )

        val telemetry = service.forRelease(releaseId)

        assertEquals(1, telemetry.states.size)
        assertEquals(1, telemetry.observations.size)
        assertEquals(OffsetDateTime.parse("2026-08-01T10:00:00Z"), telemetry.observations.single().observedAt)
    }

    @Test
    fun `store targets require their corresponding pipeline credential`() = runTest {
        val releaseId = UUID.random()
        val programId = UUID.random()
        val projectId = UUID.random()
        val versionId = UUID.random()
        val environment = Environment(id = UUID.random(), programId = programId, key = "prod", name = "Production")
        coEvery { releases.getById(releaseId) } returns Release(id = releaseId, programId = programId, name = "R")
        coEvery { releases.listVersions(releaseId) } returns listOf(ReleaseProjectVersion(releaseId, projectId, versionId))
        coEvery { versions.getById(versionId) } returns Version(id = versionId, projectId = projectId, name = "1", sequenceNumber = 1)
        coEvery { environments.listByProgram(programId) } returns listOf(environment)

        coEvery { configs.forEnvironment(projectId, "prod", any()) } returns EnvironmentDeployConfig(
            target = "google_play",
            config = json.encodeToJsonElement(
                GooglePlayTargetConfig.serializer(),
                GooglePlayTargetConfig("io.example", serviceAccountSecret = "missing-play"),
            ),
        )
        coEvery { secrets.resolve("missing-play") } returns null
        assertTrue(
            "missing-play" in assertFailsWith<IllegalStateException> { service.forRelease(releaseId) }.message.orEmpty(),
        )

        coEvery { configs.forEnvironment(projectId, "prod", any()) } returns EnvironmentDeployConfig(
            target = "app_store",
            config = json.encodeToJsonElement(
                AppStoreTargetConfig.serializer(),
                AppStoreTargetConfig("io.example", ascKeySecret = "missing-app"),
            ),
        )
        coEvery { secrets.resolve("missing-app") } returns null
        assertTrue(
            "missing-app" in assertFailsWith<IllegalStateException> { service.forRelease(releaseId) }.message.orEmpty(),
        )
    }
}
