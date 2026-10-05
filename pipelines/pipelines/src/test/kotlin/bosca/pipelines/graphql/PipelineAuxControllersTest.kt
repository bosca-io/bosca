@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.graphql

import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.git.PipelineRepoValidationError
import bosca.pipelines.git.PipelineSyncResult
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.model.PipelineSecret
import bosca.pipelines.node.NodeDescriptor
import bosca.pipelines.node.NodeFieldDescriptor
import bosca.pipelines.node.NodeInputSlot
import bosca.pipelines.node.NodeOutputSlot
import bosca.pipelines.node.NodeSettingOption
import bosca.pipelines.node.NodeSettingSlot
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Field-projection coverage for the small "auxiliary" GraphQL controllers in the pipelines module:
 *  - [PipelineNodeTypeController] / [PipelineNodeInputSlotController] / [PipelineNodeOutputSlotController]
 *    (palette descriptors, source [NodeDescriptor]).
 *  - [PipelineDryRunController] / [PipelineRunResultController] / [PipelineRepoSyncResultController] /
 *    [PipelineRepoValidationErrorController] and the [PipelineSyncResult.toModel] mapper.
 *  - [PipelineSecretController] (name + timestamps; the encrypted value is never projected).
 *
 * Every accessor is exercised, nullable accessors take BOTH the present and the absent arm, the
 * `when` in `toModel()` takes all three arms, and the result/descriptor data classes round-trip and
 * are diffed field-by-field against a `.copy()`.
 */
class PipelineAuxControllersTest {

    // ---------------------------------------------------------------------------------------------
    // PipelineNodeTypeController — NodeDescriptor projection
    // ---------------------------------------------------------------------------------------------

    private val nodeTypeController = PipelineNodeTypeController()

    private fun descriptor(): NodeDescriptor = NodeDescriptor(
        key = "transform.json",
        label = "To JSON",
        category = NodeCategory.TRANSFORM,
        group = "Core",
        subgroup = "Transform",
        inputs = listOf(
            NodeInputSlot(name = "in", typeLabel = "Object", kind = SlotKind.OBJECT),
            NodeInputSlot(name = "extra", typeLabel = "String", kind = SlotKind.STRING, required = false),
        ),
        variadic = true,
        outputs = listOf(
            NodeOutputSlot(name = "out", kind = SlotKind.OBJECT, typeLabel = "JSON"),
            NodeOutputSlot(name = "err", kind = SlotKind.ANY, error = true),
        ),
        description = "Serialize the inbound object to JSON.",
    )

    @Test
    fun `node type controller projects every descriptor field`() {
        val source = descriptor()
        assertEquals("transform.json", nodeTypeController.key(source))
        assertEquals("To JSON", nodeTypeController.label(source))
        assertEquals(NodeCategory.TRANSFORM, nodeTypeController.category(source))
        assertEquals("Core", nodeTypeController.group(source))
        assertEquals("Transform", nodeTypeController.subgroup(source))
        assertEquals("Serialize the inbound object to JSON.", nodeTypeController.description(source))
        assertEquals(source.inputs, nodeTypeController.inputs(source))
        assertEquals(2, nodeTypeController.inputs(source).size)
        assertEquals(source.outputs, nodeTypeController.outputs(source))
        assertEquals(2, nodeTypeController.outputs(source).size)
    }

    @Test
    fun `node type controller projects an empty-slot descriptor with defaults`() {
        val source = NodeDescriptor(key = "input", label = "Input", category = NodeCategory.INPUT)
        assertEquals("input", nodeTypeController.key(source))
        assertEquals("Input", nodeTypeController.label(source))
        assertEquals(NodeCategory.INPUT, nodeTypeController.category(source))
        // Unannotated nodes carry no group/subgroup — clients bucket by category instead.
        assertEquals(null, nodeTypeController.group(source))
        assertEquals(null, nodeTypeController.subgroup(source))
        assertEquals("", nodeTypeController.description(source))
        assertEquals(emptyList(), nodeTypeController.inputs(source))
        assertEquals(emptyList(), nodeTypeController.outputs(source))
        assertEquals(emptyList(), nodeTypeController.settings(source))
    }

    @Test
    fun `setting controllers project complete nested declarative metadata`() {
        val option = NodeSettingOption("prod", "Production")
        val child = NodeSettingSlot("key", SettingControl.TEXT, label = "Key")
        val setting = NodeSettingSlot(
            name = "targets",
            control = SettingControl.GROUP_LIST,
            label = "Targets",
            description = "Deployment targets",
            placeholder = "Add target",
            default = "[]",
            required = true,
            secret = true,
            mono = true,
            language = "json",
            reference = ReferenceSource.SECRET,
            options = listOf(option),
            fields = listOf(child),
            itemLabel = "Target",
            group = "Delivery",
            visibleWhenSetting = "enabled",
            visibleWhenEquals = "true",
        )
        val controller = PipelineNodeSettingSlotController()
        val optionController = PipelineNodeSettingOptionController()

        assertEquals("targets", controller.name(setting))
        assertEquals(SettingControl.GROUP_LIST, controller.control(setting))
        assertEquals("Targets", controller.label(setting))
        assertEquals("Deployment targets", controller.description(setting))
        assertEquals("Add target", controller.placeholder(setting))
        assertEquals("[]", controller.default(setting))
        assertTrue(controller.required(setting))
        assertTrue(controller.secret(setting))
        assertTrue(controller.mono(setting))
        assertEquals("json", controller.language(setting))
        assertEquals(ReferenceSource.SECRET, controller.reference(setting))
        assertEquals(listOf(option), controller.options(setting))
        assertEquals(listOf(child), controller.fields(setting))
        assertEquals("Target", controller.itemLabel(setting))
        assertEquals("Delivery", controller.group(setting))
        assertEquals("enabled", controller.visibleWhenSetting(setting))
        assertEquals("true", controller.visibleWhenEquals(setting))
        assertEquals("prod", optionController.value(option))
        assertEquals("Production", optionController.label(option))

        val defaults = NodeSettingSlot("plain", SettingControl.TEXT)
        assertNull(controller.label(defaults))
        assertNull(controller.reference(defaults))
        assertEquals(emptyList(), controller.options(defaults))
        assertNull(optionController.label(NodeSettingOption("dev")))
    }

    // ---------------------------------------------------------------------------------------------
    // PipelineNodeInputSlotController — NodeInputSlot projection (nullable type/schema both arms)
    // ---------------------------------------------------------------------------------------------

    private val inputSlotController = PipelineNodeInputSlotController()

    @Test
    fun `input slot controller projects a fully populated slot (type and schema present)`() {
        val schema = buildJsonObject { put("type", JsonPrimitive("object")) }
        val structure = listOf(NodeFieldDescriptor("id", "String"), NodeFieldDescriptor("age", "Int"))
        val source = NodeInputSlot(
            name = "payload",
            typeLabel = "Profile",
            kind = SlotKind.OBJECT,
            type = "social.Profile",
            schema = schema,
            required = true,
            structure = structure,
        )
        assertEquals("payload", inputSlotController.name(source))
        assertEquals(SlotKind.OBJECT, inputSlotController.kind(source))
        assertEquals("Profile", inputSlotController.typeLabel(source))
        assertEquals("social.Profile", inputSlotController.type(source))
        assertEquals(schema, inputSlotController.schema(source))
        assertTrue(inputSlotController.required(source))
        assertEquals(structure, inputSlotController.structure(source))
    }

    @Test
    fun `input slot controller projects null type and null schema and not-required`() {
        val source = NodeInputSlot(name = "in", typeLabel = "Any", required = false)
        assertEquals("in", inputSlotController.name(source))
        assertEquals(SlotKind.ANY, inputSlotController.kind(source))
        assertEquals("Any", inputSlotController.typeLabel(source))
        assertNull(inputSlotController.type(source))
        assertNull(inputSlotController.schema(source))
        assertEquals(false, inputSlotController.required(source))
        assertNull(inputSlotController.structure(source))
    }

    // ---------------------------------------------------------------------------------------------
    // PipelineNodeOutputSlotController — NodeOutputSlot projection (error true/false)
    // ---------------------------------------------------------------------------------------------

    private val outputSlotController = PipelineNodeOutputSlotController()

    @Test
    fun `output slot controller projects a normal output port (error false) with structure`() {
        val structure = listOf(NodeFieldDescriptor("type", "String"), NodeFieldDescriptor("coordinate", "String"))
        val source = NodeOutputSlot(name = "out", kind = SlotKind.OBJECT, type = "git.ArtifactDefinition", structure = structure)
        assertEquals("out", outputSlotController.name(source))
        assertEquals(SlotKind.OBJECT, outputSlotController.kind(source))
        assertEquals(false, outputSlotController.error(source))
        assertEquals("git.ArtifactDefinition", outputSlotController.type(source))
        assertEquals(structure, outputSlotController.structure(source))
    }

    @Test
    fun `output slot controller projects an error port (error true) with default kind and null structure`() {
        val source = NodeOutputSlot(name = "err", error = true)
        assertEquals("err", outputSlotController.name(source))
        assertEquals(SlotKind.ANY, outputSlotController.kind(source))
        assertTrue(outputSlotController.error(source))
        assertNull(outputSlotController.structure(source))
    }

    // ---------------------------------------------------------------------------------------------
    // PipelineNodeFieldDescriptorController — NodeFieldDescriptor projection (hover introspection)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `field descriptor controller projects name, type and nested fields`() {
        val controller = PipelineNodeFieldDescriptorController()
        val leaf = NodeFieldDescriptor("coordinate", "String")
        assertEquals("coordinate", controller.name(leaf))
        assertEquals("String", controller.type(leaf))
        assertNull(controller.fields(leaf))

        val nested = NodeFieldDescriptor("artifact", "ArtifactDefinition", fields = listOf(leaf))
        assertEquals(listOf(leaf), controller.fields(nested))
    }

    // ---------------------------------------------------------------------------------------------
    // PipelineDryRunController — PipelineDryRunResult projection (error present/null)
    // ---------------------------------------------------------------------------------------------

    private val dryRunController = PipelineDryRunController()

    @Test
    fun `dry run controller projects all jsonb fields and a present error`() {
        val outputs = buildJsonObject { put("a", JsonPrimitive(1)) }
        val actions = buildJsonObject { put("enqueued", JsonPrimitive(true)) }
        val errors = buildJsonObject { put("n1", JsonPrimitive("bad")) }
        val skipped = buildJsonObject { put("n2", JsonPrimitive("gated")) }
        val source = PipelineDryRunResult(outputs, actions, errors, skipped, error = "top-level boom")
        assertEquals(outputs, dryRunController.outputs(source))
        assertEquals(actions, dryRunController.actions(source))
        assertEquals(errors, dryRunController.errors(source))
        assertEquals(skipped, dryRunController.skipped(source))
        assertEquals("top-level boom", dryRunController.error(source))
    }

    @Test
    fun `dry run controller projects a null error`() {
        val source = PipelineDryRunResult(JsonNull, JsonNull, JsonNull, JsonNull, error = null)
        assertEquals(JsonNull, dryRunController.outputs(source))
        assertEquals(JsonNull, dryRunController.actions(source))
        assertEquals(JsonNull, dryRunController.errors(source))
        assertEquals(JsonNull, dryRunController.skipped(source))
        assertNull(dryRunController.error(source))
    }

    @Test
    fun `PipelineDryRunResult equals differs in each field via copy`() {
        val base = PipelineDryRunResult(
            outputs = buildJsonObject { put("o", JsonPrimitive(1)) },
            actions = buildJsonObject { put("a", JsonPrimitive(1)) },
            errors = buildJsonObject { put("e", JsonPrimitive(1)) },
            skipped = buildJsonObject { put("s", JsonPrimitive(1)) },
            error = "x",
        )
        assertEquals(base, base.copy())
        assertNotEquals(base, base.copy(outputs = JsonNull))
        assertNotEquals(base, base.copy(actions = JsonNull))
        assertNotEquals(base, base.copy(errors = JsonNull))
        assertNotEquals(base, base.copy(skipped = JsonNull))
        assertNotEquals(base, base.copy(error = null))
        assertNotEquals(base, base.copy(error = "y"))
    }

    // ---------------------------------------------------------------------------------------------
    // PipelineRunResultController — PipelineRunResultModel projection (output/error present/null)
    // ---------------------------------------------------------------------------------------------

    private val runResultController = PipelineRunResultController()

    @Test
    fun `run result controller projects a successful run with output and no error`() {
        val runId = UUID.random()
        val output = buildJsonObject { put("result", JsonPrimitive("done")) }
        val source = PipelineRunResultModel(
            ok = true,
            runId = runId,
            status = PipelineRunStatus.OK,
            output = output,
            error = null,
        )
        assertTrue(runResultController.ok(source))
        assertEquals(runId, runResultController.runId(source))
        assertEquals(PipelineRunStatus.OK, runResultController.status(source))
        assertEquals(output, runResultController.output(source))
        assertNull(runResultController.error(source))
    }

    @Test
    fun `run result controller projects a failed run with error and null output`() {
        val runId = UUID.random()
        val source = PipelineRunResultModel(
            ok = false,
            runId = runId,
            status = PipelineRunStatus.FAILED,
            output = null,
            error = "node failed",
        )
        assertEquals(false, runResultController.ok(source))
        assertEquals(runId, runResultController.runId(source))
        assertEquals(PipelineRunStatus.FAILED, runResultController.status(source))
        assertNull(runResultController.output(source))
        assertEquals("node failed", runResultController.error(source))
    }

    @Test
    fun `PipelineRunResultModel equals differs in each field via copy`() {
        val runId = UUID.random()
        val base = PipelineRunResultModel(
            ok = true,
            runId = runId,
            status = PipelineRunStatus.OK,
            output = buildJsonObject { put("o", JsonPrimitive(1)) },
            error = null,
        )
        assertEquals(base, base.copy())
        assertNotEquals(base, base.copy(ok = false))
        assertNotEquals(base, base.copy(runId = UUID.random()))
        assertNotEquals(base, base.copy(status = PipelineRunStatus.SUSPENDED))
        assertNotEquals(base, base.copy(output = null))
        assertNotEquals(base, base.copy(error = "boom"))
    }

    // ---------------------------------------------------------------------------------------------
    // PipelineSyncResult.toModel() — all three sealed arms
    // ---------------------------------------------------------------------------------------------

    private val syncResultController = PipelineRepoSyncResultController()
    private val validationErrorController = PipelineRepoValidationErrorController()

    @Test
    fun `toModel maps Ok with a commit sha`() {
        val model = PipelineSyncResult.Ok(commitSha = "abc123").toModel()
        assertTrue(model.ok)
        assertEquals("abc123", model.commitSha)
        assertNull(model.validationErrors)
        assertNull(model.errorMessage)
    }

    @Test
    fun `toModel maps Ok with a null commit sha (default arg)`() {
        val model = PipelineSyncResult.Ok().toModel()
        assertTrue(model.ok)
        assertNull(model.commitSha)
        assertNull(model.validationErrors)
        assertNull(model.errorMessage)
    }

    @Test
    fun `toModel maps ValidationFailed carrying the errors`() {
        val errors = listOf(
            PipelineRepoValidationError(path = "pipelines/a.yaml", message = "missing key"),
            PipelineRepoValidationError(path = "pipelines/b.yaml", message = "bad node"),
        )
        val model = PipelineSyncResult.ValidationFailed(errors).toModel()
        assertEquals(false, model.ok)
        assertNull(model.commitSha)
        assertEquals(errors, model.validationErrors)
        assertNull(model.errorMessage)
    }

    @Test
    fun `toModel maps Failure carrying the message`() {
        val model = PipelineSyncResult.Failure(message = "io error").toModel()
        assertEquals(false, model.ok)
        assertNull(model.commitSha)
        assertNull(model.validationErrors)
        assertEquals("io error", model.errorMessage)
    }

    // ---------------------------------------------------------------------------------------------
    // PipelineRepoSyncResultController — projection of each field (present + null arms)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `sync result controller projects an ok model (sha present, errors and message null)`() {
        val source = PipelineRepoSyncResultModel(ok = true, commitSha = "deadbeef", validationErrors = null, errorMessage = null)
        assertTrue(syncResultController.ok(source))
        assertEquals("deadbeef", syncResultController.commitSha(source))
        assertNull(syncResultController.validationErrors(source))
        assertNull(syncResultController.errorMessage(source))
    }

    @Test
    fun `sync result controller projects a validation-failed model (errors present, sha and message null)`() {
        val errors = listOf(PipelineRepoValidationError(path = "p", message = "m"))
        val source = PipelineRepoSyncResultModel(ok = false, commitSha = null, validationErrors = errors, errorMessage = null)
        assertEquals(false, syncResultController.ok(source))
        assertNull(syncResultController.commitSha(source))
        assertEquals(errors, syncResultController.validationErrors(source))
        assertNull(syncResultController.errorMessage(source))
    }

    @Test
    fun `sync result controller projects a failure model (message present, sha and errors null)`() {
        val source = PipelineRepoSyncResultModel(ok = false, commitSha = null, validationErrors = null, errorMessage = "boom")
        assertEquals(false, syncResultController.ok(source))
        assertNull(syncResultController.commitSha(source))
        assertNull(syncResultController.validationErrors(source))
        assertEquals("boom", syncResultController.errorMessage(source))
    }

    @Test
    fun `PipelineRepoSyncResultModel equals differs in each field via copy`() {
        val base = PipelineRepoSyncResultModel(
            ok = true,
            commitSha = "sha",
            validationErrors = listOf(PipelineRepoValidationError("p", "m")),
            errorMessage = "e",
        )
        assertEquals(base, base.copy())
        assertNotEquals(base, base.copy(ok = false))
        assertNotEquals(base, base.copy(commitSha = null))
        assertNotEquals(base, base.copy(validationErrors = null))
        assertNotEquals(base, base.copy(errorMessage = null))
    }

    // ---------------------------------------------------------------------------------------------
    // PipelineRepoValidationErrorController — path + message
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `validation error controller projects path and message`() {
        val source = PipelineRepoValidationError(path = "pipelines/x.yaml", message = "unknown node key")
        assertEquals("pipelines/x.yaml", validationErrorController.path(source))
        assertEquals("unknown node key", validationErrorController.message(source))
    }

    // ---------------------------------------------------------------------------------------------
    // PipelineSecretController — name + timestamps only (never the encrypted value)
    // ---------------------------------------------------------------------------------------------

    private val secretController = PipelineSecretController()

    @Test
    fun `secret controller projects name and timestamps`() {
        val created = OffsetDateTime.parse("2026-06-18T10:00:00Z")
        val modified = OffsetDateTime.parse("2026-06-19T11:30:00Z")
        val source = PipelineSecret(
            name = "stripe-key",
            encryptedValue = "ciphertext-not-projected",
            createdAt = created,
            modifiedAt = modified,
        )
        assertEquals("stripe-key", secretController.name(source))
        assertEquals(created, secretController.createdAt(source))
        assertEquals(modified, secretController.modifiedAt(source))
    }

    @Test
    fun `secret controller honors default timestamps when only a name is supplied`() {
        val source = PipelineSecret(name = "token")
        assertEquals("token", secretController.name(source))
        // createdAt/modifiedAt default to OffsetDateTime.now(); just exercise the accessors.
        assertEquals(source.createdAt, secretController.createdAt(source))
        assertEquals(source.modifiedAt, secretController.modifiedAt(source))
    }
}
