package bosca.workops.controller

import bosca.serialization.OffsetDateTime
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.workops.deploy.AppStoreTargetConfig
import bosca.workops.deploy.GooglePlayTargetConfig
import bosca.workops.model.artifact.BreakingChangeLevel
import bosca.workops.model.environment.EnvironmentTargetType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ControllerInputSerializationTest {

    private val json = Json {
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
            contextual(OffsetDateTimeSerializer())
        }
    }
    private val explicitJson = Json(json) { encodeDefaults = true }

    @Test
    fun `release inputs decode omitted optionals and round trip authored values`() {
        val programId = UUID.random()
        val minimalConstructed = CreateReleaseInput(programId, "1.0")
        assertEquals(minimalConstructed, roundTrip(CreateReleaseInput.serializer(), minimalConstructed))
        assertEquals(minimalConstructed, roundTrip(CreateReleaseInput.serializer(), minimalConstructed, explicitJson))
        val minimal = json.decodeFromString(
            CreateReleaseInput.serializer(),
            """{"programId":"$programId","name":"1.0"}""",
        )
        assertNull(minimal.description)
        assertNull(minimal.releaseDate)
        assertNull(minimal.ownerProfileId)

        val full = CreateReleaseInput(
            programId = programId,
            name = "1.1",
            description = "Release notes",
            releaseDate = OffsetDateTime.parse("2026-09-01T12:00:00Z"),
            ownerProfileId = UUID.random(),
        )
        assertEquals(full, roundTrip(CreateReleaseInput.serializer(), full))
        assertEquals(full, roundTrip(CreateReleaseInput.serializer(), full, explicitJson))

        val updateConstructed = UpdateReleaseInput("1.2")
        assertEquals(updateConstructed, roundTrip(UpdateReleaseInput.serializer(), updateConstructed))
        assertEquals(updateConstructed, roundTrip(UpdateReleaseInput.serializer(), updateConstructed, explicitJson))
        val updateMinimal = json.decodeFromString(UpdateReleaseInput.serializer(), """{"name":"1.2"}""")
        assertNull(updateMinimal.description)
        assertNull(updateMinimal.releaseDate)
        val update = UpdateReleaseInput("1.2", "Updated", OffsetDateTime.parse("2026-10-01T12:00:00Z"))
        assertEquals(update, roundTrip(UpdateReleaseInput.serializer(), update))
        assertEquals(update, roundTrip(UpdateReleaseInput.serializer(), update, explicitJson))

        val promotionConstructed = PromoteReleaseInput("prod")
        assertEquals(promotionConstructed, roundTrip(PromoteReleaseInput.serializer(), promotionConstructed))
        assertEquals(promotionConstructed, roundTrip(PromoteReleaseInput.serializer(), promotionConstructed, explicitJson))
        val promotionMinimal = json.decodeFromString(PromoteReleaseInput.serializer(), """{"environment":"prod"}""")
        assertEquals(false, promotionMinimal.allowDowngrade)
        assertNull(promotionMinimal.inputs)
        val promotion = PromoteReleaseInput("staging", true, buildJsonObject { put("track", "beta") })
        assertEquals(promotion, roundTrip(PromoteReleaseInput.serializer(), promotion))
        assertEquals(promotion, roundTrip(PromoteReleaseInput.serializer(), promotion, explicitJson))
    }

    @Test
    fun `move task input distinguishes omitted and authored field mappings`() {
        val target = UUID.random()
        val statusMapping = buildJsonObject { put("todo", "doing") }
        val minimal = MoveTaskInput(target, statusMapping)
        val decodedMinimal = roundTrip(MoveTaskInput.serializer(), minimal)
        assertNull(decodedMinimal.fieldMapping)
        assertEquals(minimal, roundTrip(MoveTaskInput.serializer(), minimal, explicitJson))

        val full = MoveTaskInput(target, statusMapping, buildJsonObject { put("priority", "severity") })
        assertEquals(full, roundTrip(MoveTaskInput.serializer(), full))
        assertEquals(full, roundTrip(MoveTaskInput.serializer(), full, explicitJson))

        val patch = StartPatchReleaseInput(listOf(UUID.random(), UUID.random()))
        assertEquals(patch, roundTrip(StartPatchReleaseInput.serializer(), patch))
        assertEquals(patch, roundTrip(StartPatchReleaseInput.serializer(), patch, explicitJson))
    }

    @Test
    fun `controller inputs reject missing required wire fields`() {
        listOf(
            CreateReleaseInput.serializer(),
            MoveTaskInput.serializer(),
            UpdateReleaseInput.serializer(),
            PromoteReleaseInput.serializer(),
            StartPatchReleaseInput.serializer(),
        ).forEach { serializer ->
            assertFailsWith<SerializationException> {
                json.decodeFromString(serializer, "{}")
            }
        }
    }

    @Test
    fun `notification preference input validates its complete required envelope`() {
        val input = NotificationPreferenceUpdateInput(
            eventChannels = JsonObject(emptyMap()),
            watchAuthored = true,
            watchCommented = false,
            dailyDigest = true,
            dndStartLocal = null,
            dndEndLocal = "07:00",
            mutedTaskIds = listOf(UUID.random()),
            mutedProjectIds = listOf(UUID.random()),
        )

        assertEquals(input, roundTrip(NotificationPreferenceUpdateInput.serializer(), input))
        assertFailsWith<SerializationException> {
            json.decodeFromString(NotificationPreferenceUpdateInput.serializer(), "{}")
        }
    }

    @Test
    fun `environment patch input preserves omitted partial fields and full patches`() {
        val typeId = UUID.random()
        val minimalConstructed = PatchEnvironmentInput(name = "Production", typeId = typeId)
        assertEquals(minimalConstructed, roundTrip(PatchEnvironmentInput.serializer(), minimalConstructed))
        assertEquals(minimalConstructed, roundTrip(PatchEnvironmentInput.serializer(), minimalConstructed, explicitJson))
        val minimal = json.decodeFromString(
            PatchEnvironmentInput.serializer(),
            """{"name":"Production","typeId":"$typeId"}""",
        )
        assertNull(minimal.description)
        assertNull(minimal.displayOrder)
        assertEquals(emptyList(), minimal.promotionSourceIds)
        assertNull(minimal.requiresApproval)
        assertNull(minimal.autoPromote)
        assertNull(minimal.targetType)
        assertNull(minimal.targetRef)
        assertNull(minimal.ephemeral)

        val full = PatchEnvironmentInput(
            name = "Canary",
            description = "Early rollout",
            displayOrder = 3,
            promotionSourceIds = listOf(UUID.random(), UUID.random()),
            requiresApproval = true,
            autoPromote = false,
            typeId = typeId,
            targetType = EnvironmentTargetType.PLAY_TRACK,
            targetRef = "beta",
            ephemeral = true,
        )
        assertEquals(full, roundTrip(PatchEnvironmentInput.serializer(), full))
        assertEquals(full, roundTrip(PatchEnvironmentInput.serializer(), full, explicitJson))

        val independentlyOmitted = PatchEnvironmentInput(
            name = "Canary",
            displayOrder = 3,
            promotionSourceIds = listOf(UUID.random()),
            requiresApproval = true,
            autoPromote = false,
            typeId = typeId,
            targetType = EnvironmentTargetType.PLAY_TRACK,
            targetRef = "beta",
            ephemeral = true,
        )
        assertNull(independentlyOmitted.description)
        assertEquals(independentlyOmitted, roundTrip(PatchEnvironmentInput.serializer(), independentlyOmitted))
    }

    @Test
    fun `store target configs preserve each authored setting when one default is omitted`() {
        val minimalAppStore = AppStoreTargetConfig(bundleId = "io.example.minimal")
        assertEquals(minimalAppStore, roundTrip(AppStoreTargetConfig.serializer(), minimalAppStore))
        assertEquals(minimalAppStore, roundTrip(AppStoreTargetConfig.serializer(), minimalAppStore, explicitJson))
        val appStore = AppStoreTargetConfig(
            bundleId = "io.example.app",
            testflightGroups = listOf("Internal", "Beta"),
            testflightGroup = "Legacy",
            phasedRelease = true,
            ascKeySecret = "asc-key",
            releaseNotesLocales = listOf("en-US", "fr-FR"),
        )
        assertEquals("default", appStore.buildNumberKey)
        assertEquals(listOf("Internal", "Beta", "Legacy"), appStore.betaGroups())
        assertEquals(appStore, roundTrip(AppStoreTargetConfig.serializer(), appStore))
        assertEquals(appStore, roundTrip(AppStoreTargetConfig.serializer(), appStore, explicitJson))

        val minimalGooglePlay = GooglePlayTargetConfig(packageName = "io.example.minimal")
        assertEquals(minimalGooglePlay, roundTrip(GooglePlayTargetConfig.serializer(), minimalGooglePlay))
        assertEquals(minimalGooglePlay, roundTrip(GooglePlayTargetConfig.serializer(), minimalGooglePlay, explicitJson))
        val googlePlay = GooglePlayTargetConfig(
            packageName = "io.example.app",
            track = "production",
            serviceAccountSecret = "play-service-account",
            rolloutPercentage = 25.0,
            releaseNotesLocales = listOf("en-US", "de-DE"),
        )
        assertEquals("default", googlePlay.buildNumberKey)
        assertEquals(googlePlay, roundTrip(GooglePlayTargetConfig.serializer(), googlePlay))
        assertEquals(googlePlay, roundTrip(GooglePlayTargetConfig.serializer(), googlePlay, explicitJson))
    }

    @Test
    fun `api report input preserves optional publication and report URL`() {
        val minimal = RegisterApiSurfaceReportInput(
            projectId = UUID.random(),
            versionId = UUID.random(),
            previousVersionId = UUID.random(),
            breakingChangeLevel = BreakingChangeLevel.NONE,
            changes = JsonArray(emptyList()),
            analyzerTool = "binary-compatibility-validator",
        )
        val decodedMinimal = roundTrip(RegisterApiSurfaceReportInput.serializer(), minimal)
        assertEquals(minimal, roundTrip(RegisterApiSurfaceReportInput.serializer(), minimal, explicitJson))
        assertNull(decodedMinimal.artifactPublicationId)
        assertNull(decodedMinimal.reportUrl)

        val full = minimal.copy(
            artifactPublicationId = UUID.random(),
            breakingChangeLevel = BreakingChangeLevel.MAJOR_BREAKING,
            changes = JsonArray(listOf(JsonPrimitive("removed method"))),
            reportUrl = "https://reports.invalid/api/42",
        )
        assertEquals(full, roundTrip(RegisterApiSurfaceReportInput.serializer(), full))
        assertEquals(full, roundTrip(RegisterApiSurfaceReportInput.serializer(), full, explicitJson))
    }

    @Test
    fun `automation document input preserves nullable scope metadata`() {
        val base = AutomationRuleDocumentInput(
            scope = "PROJECT",
            name = "Notify owner",
            enabled = true,
            trigger = buildJsonObject { put("type", "TaskCreated") },
            conditions = JsonArray(emptyList()),
            actions = JsonArray(emptyList()),
            runAsProfileId = UUID.random(),
            failureMode = "CONTINUE",
            executionLogRetentionDays = 30,
            maxFiresPerTaskPerHour = 2,
        )
        val decodedBase = roundTrip(AutomationRuleDocumentInput.serializer(), base)
        assertEquals(base, roundTrip(AutomationRuleDocumentInput.serializer(), base, explicitJson))
        assertNull(decodedBase.scopeId)
        assertNull(decodedBase.description)

        val full = base.copy(scopeId = UUID.random(), description = "Notify when work starts")
        assertEquals(full, roundTrip(AutomationRuleDocumentInput.serializer(), full))
        assertEquals(full, roundTrip(AutomationRuleDocumentInput.serializer(), full, explicitJson))
    }

    @Test
    fun `working calendar input round trips wire JSON and nullable description`() {
        val minimal = CreateWorkingCalendarInput(
            name = "Default",
            timeZone = "America/Chicago",
            weeklyHours = JsonObject(emptyMap()),
            holidays = JsonArray(emptyList()),
        )
        val decodedMinimal = roundTrip(CreateWorkingCalendarInput.serializer(), minimal)
        assertNull(decodedMinimal.description)

        val full = minimal.copy(description = "Company calendar")
        assertEquals(full, roundTrip(CreateWorkingCalendarInput.serializer(), full))
    }

    @Test
    fun `controller inputs reject JSON missing required fields`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(CreateReleaseInput.serializer(), "{}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString(PatchEnvironmentInput.serializer(), "{}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString(RegisterApiSurfaceReportInput.serializer(), "{}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString(AutomationRuleDocumentInput.serializer(), "{}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString(CreateWorkingCalendarInput.serializer(), "{}")
        }
    }

    private fun <T> roundTrip(
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
        format: Json = json,
    ): T = format.decodeFromString(serializer, format.encodeToString(serializer, value))
}
