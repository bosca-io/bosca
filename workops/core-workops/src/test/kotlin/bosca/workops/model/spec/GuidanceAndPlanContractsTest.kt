package bosca.workops.model.spec

import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.workops.model.guidance.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.*

class GuidanceAndPlanContractsTest {
    private val json = Json { serializersModule = SerializersModule { contextual(UUID::class, UUIDSerializer()); contextual(bosca.serialization.OffsetDateTime::class, bosca.serialization.OffsetDateTimeSerializer()) } }
    private val explicit = Json(json) { encodeDefaults = true }

    private fun <T> roundTrip(serializer: KSerializer<T>, value: T, required: List<String> = emptyList()) {
        val encoded = json.encodeToJsonElement(serializer, value).jsonObject
        assertEquals(value, json.decodeFromJsonElement(serializer, encoded))
        assertEquals(value, json.decodeFromJsonElement(serializer, explicit.encodeToJsonElement(serializer, value)))
        for (field in required) assertFailsWith<SerializationException>(field) { json.decodeFromJsonElement(serializer, JsonObject(encoded - field)) }
    }

    @Test fun `guidance defaults enforce reviewed interfaces TDD and high coverage while null and omitted inputs inherit`() {
        val defaults = json.decodeFromString(WorkOpsGuidancePolicy.serializer(), "{}")
        assertTrue(defaults.requireInterfaceReview && defaults.requireTdd && defaults.requireTestQuality)
        assertEquals(99.0, defaults.minimumLineCoverage); assertEquals(97.0, defaults.minimumBranchCoverage)
        assertEquals(WorkOpsGuidancePolicy(), defaults)
        roundTrip(WorkOpsGuidancePolicy.serializer(), defaults)
        roundTrip(WorkOpsGuidancePolicy.serializer(), defaults.copy(requireInterfaceReview = false, requireTdd = false, requireTestQuality = false, minimumLineCoverage = 100.0, minimumBranchCoverage = 100.0))
        assertEquals(WorkOpsGuidanceInput(), json.decodeFromString(WorkOpsGuidanceInput.serializer(), "{}"))
        roundTrip(WorkOpsGuidanceInput.serializer(), WorkOpsGuidanceInput())
        roundTrip(WorkOpsGuidanceInput.serializer(), WorkOpsGuidanceInput(defaults))
        val guide = WorkOpsGuideInput(WorkOpsGuidancePhase.INVESTIGATION, "investigate", "Use when investigating", "# Read source")
        roundTrip(WorkOpsGuideInput.serializer(), guide, listOf("phase", "name", "description", "markdown"))
        roundTrip(WorkOpsGuidanceInput.serializer(), WorkOpsGuidanceInput(guides = listOf(guide)))
        roundTrip(WorkOpsGuidanceInput.serializer(), WorkOpsGuidanceInput(defaults, listOf(guide)))
        val bundled = WorkOpsGuide(guide.phase, guide.name, guide.description, guide.markdown, true)
        assertNull(bundled.metadataId); assertNull(bundled.documentVersion); assertNull(bundled.resourceId)
        roundTrip(WorkOpsGuide.serializer(), bundled, listOf("phase", "name", "description", "markdown", "inherited"))
        val stored = bundled.copy(inherited = false, metadataId = UUID.random(), documentVersion = 7, resourceId = UUID.random())
        roundTrip(WorkOpsGuide.serializer(), stored)
        roundTrip(WorkOpsGuidance.serializer(), WorkOpsGuidance("revision", defaults, true, listOf(bundled, stored)), listOf("revision", "policy", "policyInherited", "guides"))
        assertEquals(WorkOpsGuidancePhase.entries.map { it.name }, WorkOpsGuidancePhase.entries.map { json.decodeFromString(WorkOpsGuidancePhase.serializer(), json.encodeToString(WorkOpsGuidancePhase.serializer(), it)).name })
    }

    @Test fun `completion revision is independent from the ordinary spec version and round trips explicitly`() {
        val id = UUID.random()
        val base = Spec(id = id, key = "SPEC-1", metadataId = UUID.random(), statusId = UUID.random(), workflowId = UUID.random(), ownerProfileId = UUID.random(), createdByPrincipalId = UUID.random(), modifiedByPrincipalId = UUID.random(), version = 8)
        assertEquals(0L, base.completionVersion)
        val changed = base.copy(completionVersion = 12)
        assertEquals(8L, changed.version)
        roundTrip(Spec.serializer(), base)
        roundTrip(Spec.serializer(), changed)
        assertEquals(12L, explicit.encodeToJsonElement(Spec.serializer(), changed).jsonObject.getValue("completionVersion").jsonPrimitive.long)
    }

    @Test fun `spec snapshots preserve identity scope lifecycle and optional values across serialization`() {
        val id = UUID.random()
        val required = listOf("key", "metadataId", "statusId", "workflowId", "ownerProfileId", "createdByPrincipalId", "modifiedByPrincipalId")
        val base = Spec(key = "SPEC-1", metadataId = id, statusId = id, workflowId = id, ownerProfileId = id, createdByPrincipalId = id, modifiedByPrincipalId = id)
        val minimal = explicit.encodeToJsonElement(Spec.serializer(), base).jsonObject.filterKeys { it in required }
        val defaults = json.decodeFromJsonElement(Spec.serializer(), JsonObject(minimal))
        assertEquals(UUID.NIL, defaults.id); assertEquals(0L, defaults.version); assertEquals(0L, defaults.completionVersion)
        assertFalse(defaults.isDeleted); assertTrue(defaults.isPublished); assertFalse(defaults.isAdvertised)
        roundTrip(Spec.serializer(), base, required)
        assertFailsWith<IllegalArgumentException> { base.copy(key = " ") }
        val full = base.copy(id = UUID.random(), programId = UUID.random(), projectId = UUID.random(), parentSpecId = UUID.random(), sortOrder = 7,
            childCount = 2, childDoneCount = 1, gitRepositoryId = UUID.random(), gitPath = "src", watcherProfileIds = listOf(id), labelIds = listOf(id),
            externalReferences = buildJsonObject { put("source", "test") }, public = true, publicContent = true, publicList = true, publicSupplementary = true,
            deletedAt = bosca.serialization.OffsetDateTime.now(), version = 9, completionVersion = 12)
        roundTrip(Spec.serializer(), full)
        val restored = json.decodeFromJsonElement(Spec.serializer(), explicit.encodeToJsonElement(Spec.serializer(), full))
        assertEquals(full.metadataId, restored.metadataId); assertEquals(full.ownerProfileId, restored.ownerProfileId)
        assertEquals(full.statusId, restored.statusId); assertEquals(full.workflowId, restored.workflowId)
        assertEquals(full.createdByPrincipalId, restored.createdByPrincipalId); assertEquals(full.modifiedByPrincipalId, restored.modifiedByPrincipalId)
        assertTrue(restored.isDeleted); assertEquals(12L, restored.completionVersion)
    }

    @Test fun `atomic plan inputs preserve all version checks and reject missing content or identities`() {
        val content = RequirementContentInput("Title", "Body", listOf("Behavior works"))
        roundTrip(RequirementContentInput.serializer(), content, listOf("name", "markdownContent", "acceptanceCriteria"))
        val request = UUID.random(); val project = UUID.random()
        roundTrip(CreateSpecPlanInput.serializer(), CreateSpecPlanInput(request, project, "Plan", "Scope", listOf(content)), listOf("requestId", "projectId", "name", "markdownContent", "requirements"))
        val replacement = SetRequirementContentInput(content, 2, 3, 4)
        roundTrip(SetRequirementContentInput.serializer(), replacement, listOf("content", "expectedVersion", "expectedTaskVersion", "metadataVersion"))
        assertEquals(listOf(2L, 3L, 4L), listOf(replacement.expectedVersion, replacement.expectedTaskVersion, replacement.metadataVersion.toLong()))
        val receipt = PlanCreation(request, UUID.random(), "fingerprint", null)
        assertEquals(request, receipt.key); assertNull(receipt.targetId)
        assertEquals(receipt.fingerprint, receipt.copy(targetId = UUID.random()).fingerprint)
    }
}
