package bosca.workops.pipeline

import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import bosca.workops.model.environment.Environment
import bosca.workops.model.release.Release
import bosca.workops.service.EnvironmentService
import bosca.workops.service.EnvironmentTypeService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * resolves a release [Environment] by **environment type** for a Deploy/Promote
 * node. Environment types (development / staging / production / …) are a global, user-managed catalog,
 * so a type reference is portable across programs that don't share environment names: the node reads
 * the inbound [Release]'s program and resolves the program's environment of [environmentType] at run
 * time. [environmentName] is an optional exact-name override for programs with several environments of
 * one type. Output carries `Environment.serializer()`, so the typed environment flows into a
 * environment-oriented pipeline operations. Sibling of [GetVersionNode].
 *
 * [fallbackEnvironmentTypes] makes a partially-configured program degrade gracefully: the types are
 * tried in order after the primary, and the first one the program has an environment of wins. The
 * pipeline templates can use it to collapse environment ladders onto whatever exists (development → staging →
 * production), so a single-environment program still releases.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Environment",
    description = "Resolves the release program's environment of a global environment type, to feed a Deploy or Promote node.",
    group = "WorkOps",
    subgroup = "Environments",
    inputs = [
        InputSlot(
            name = "in", kind = SlotKind.OBJECT, type = Release::class, typeLabel = "Release",
            description = "The release whose program the environment belongs to — its program scopes the lookup.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out", kind = SlotKind.OBJECT, type = Environment::class, typeLabel = "Environment",
            description = "The resolved environment (typed).",
        ),
    ],
    settings = [
        SettingSlot(
            name = "environmentType", control = SettingControl.REFERENCE, reference = ReferenceSource.ENVIRONMENT_TYPE,
            label = "Environment type", required = true, placeholder = "e.g. production",
            description = "The global environment type — resolved to the concrete environment within the run's own program.",
        ),
        SettingSlot(
            name = "environmentName", control = SettingControl.TEXT,
            label = "Exact name (optional)", placeholder = "only when a program has several environments of this type",
            description = "Overrides type resolution with an exact program-unique environment name.",
        ),
        SettingSlot(
            name = "fallbackEnvironmentTypes", control = SettingControl.LIST,
            label = "Fallback types (ordered)", placeholder = "e.g. staging, production",
            description = "Tried in order when the program has no environment of the primary type — the first type with a configured environment wins, so partially-configured programs degrade gracefully.",
        ),
        SettingSlot(
            name = "optional", control = SettingControl.BOOLEAN, label = "Optional",
            description = "When the program has no environment of this type, emit nothing (the channel downstream skips) instead of failing — for channels not every program has, like app-store tracks.",
        ),
    ],
)
@Serializable
@SerialName("environment.get")
class GetEnvironmentNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** The global environment type's name, resolved within the inbound release's program. */
    val environmentType: String = "",
    /** Optional exact-name override (program-unique), for programs with several environments of one type. */
    val environmentName: String = "",
    /** Types tried in order after [environmentType] when the program has no environment of it. */
    val fallbackEnvironmentTypes: List<String> = emptyList(),
    /** When the program has no environment of any tried type, emit nothing (downstream skips) instead of failing. */
    val optional: Boolean = false,
    override val position: NodePosition = NodePosition(),
) : TransformNode() {

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val label = name.ifBlank { id }
        val release = GetEnvironmentNodeSerializer.deserialize(context, inputs).`in`
        val environment = if (environmentName.isNotBlank()) {
            provide<EnvironmentService>().getByProgramAndName(release.programId, environmentName)
                ?: if (optional) return null
                else error("Get Environment node '$label': no environment named '$environmentName' in the release's program")
        } else {
            if (environmentType.isBlank()) error("Get Environment node '$label' needs an environment type")
            val chain = (listOf(environmentType) + fallbackEnvironmentTypes)
                .map { it.trim() }.filter { it.isNotBlank() }.distinct()
            resolveFirstOfChain(label, release.programId, chain)
                ?: if (optional) return null
                else error(
                    "Get Environment node '$label': the release's program has no '${chain.first()}' environment" +
                        if (chain.size > 1) " (also tried: ${chain.drop(1).joinToString()})" else "",
                )
        }
        return GetEnvironmentNodeSerializer.serialize(environment)
    }

    /**
     * The program's environment of the first type in [chain] that has one — null when none do. An
     * ambiguous rung (several environments of one type) fails immediately rather than falling through:
     * silently skipping past a rung that exists would deploy somewhere the author didn't pick.
     *
     * A type name missing from the global catalog is an authoring error only for a single-type,
     * non-optional node (the original strict behavior, and where a typo has no other way to surface).
     * Optional nodes keep their "unknown type = nothing to do" semantics — the store-channel templates
     * reference types (google-play, app-store) that many installs never seed — and a multi-type chain
     * skips the unknown rung, surfacing it in the exhausted error's tried list instead.
     */
    private suspend fun resolveFirstOfChain(label: String, programId: bosca.serialization.UUID, chain: List<String>): Environment? {
        val typeService = provide<EnvironmentTypeService>()
        val environmentService = provide<EnvironmentService>()
        for (typeName in chain) {
            val type = typeService.getByName(typeName)
            if (type == null) {
                if (!optional && chain.size == 1) {
                    error("Get Environment node '$label': unknown environment type '$typeName'")
                }
                continue
            }
            val candidates = environmentService.listByProgramAndType(programId, type.id)
            when {
                candidates.isEmpty() -> continue
                candidates.size > 1 -> error(
                    "Get Environment node '$label': the release's program has ${candidates.size} '${type.name}' " +
                        "environments (${candidates.joinToString { it.name }}) — set the node's exact name to pick one",
                )
                else -> return candidates.single()
            }
        }
        return null
    }
}
