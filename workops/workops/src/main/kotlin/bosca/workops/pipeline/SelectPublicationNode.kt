package bosca.workops.pipeline

import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingOption
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import bosca.workops.model.artifact.ArtifactPublication
import bosca.workops.model.artifact.ArtifactType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * selects ONE [ArtifactPublication] from a version's publications by its
 * [artifactType]. A version usually publishes several artifacts of different types (a Docker image, an
 * Android bundle, an iOS build, a Maven package …) and each deploys to a different channel — so a relay
 * places one Select Publication per target environment ("the HELM_VALUES publication → the cluster environment",
 * "the ANDROID_AAR → the Play track") and feeds a Deploy to Environment node. Explicit and typed, where a
 * JSONata filter would hide the intent.
 *
 * A version with NO publication of the selected type emits nothing, so the channel downstream of this
 * node simply doesn't run — the relay is a GLOBAL pipeline shared by every project, and a project that
 * doesn't publish this artifact type has nothing to deploy on this channel. (A *declared* artifact that
 * failed to land in the registry fails the CI job instead — that is where a broken build is caught.)
 */
@PipelineNodeType(
    category = NodeCategory.TRANSFORM,
    label = "Select Publication",
    description = "Selects the artifact publication of a given type from a version's publications, for a Deploy. Emits nothing when the version has none — the channel downstream simply doesn't run.",
    group = "WorkOps",
    subgroup = "Artifacts",
    inputs = [
        InputSlot(
            name = "in", kind = SlotKind.ARRAY, type = ArtifactPublication::class, typeLabel = "Publications",
            description = "The version's artifact publications — an Associate Artifact node's output.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out", kind = SlotKind.OBJECT, type = ArtifactPublication::class, typeLabel = "Publication",
            description = "The publication of the selected artifact type.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "artifactType", control = SettingControl.ENUM, label = "Artifact type",
            default = "DOCKER", required = true,
            description = "The publication type this environment deploys — e.g. DOCKER for an API environment, ANDROID_AAR for a Play track.",
            options = [
                SettingOption("HELM_VALUES", "Helm values"),
                SettingOption("HELM", "Helm chart"),
                SettingOption("DOCKER", "Docker image"),
                SettingOption("ANDROID_AAR", "Android bundle"),
                SettingOption("IOS_FRAMEWORK", "iOS build"),
                SettingOption("MAVEN", "Maven package"),
                SettingOption("NPM", "npm package"),
                SettingOption("GRAALVM_NATIVE", "GraalVM native binary"),
                SettingOption("WASM", "WebAssembly"),
                SettingOption("OTHER", "Other"),
            ],
        ),
    ],
)
@Serializable
@SerialName("artifact.select")
class SelectPublicationNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** The publication type to select — the deployable this node's target environment expects. */
    val artifactType: ArtifactType = ArtifactType.DOCKER,
    override val position: NodePosition = NodePosition(),
) : TransformNode() {

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val publications = SelectPublicationNodeSerializer.deserialize(context, inputs).`in`
        // No publication of this type = nothing to deploy on this channel: emit nothing and the
        // downstream chain skips (the executor cascades the skip).
        val selected = publications.find { it.artifactType == artifactType } ?: return null
        return SelectPublicationNodeSerializer.serialize(selected)
    }
}
