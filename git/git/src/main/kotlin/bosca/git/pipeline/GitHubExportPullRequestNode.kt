package bosca.git.pipeline

import bosca.di.provide
import bosca.git.model.PullRequestEvent
import bosca.git.service.GitHubSyncService
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@PipelineNodeType(
    category = NodeCategory.ACTION, label = "Export GitHub Pull Requests",
    description = "Export current Bosca pull request lifecycle changes.",
    group = "Git", subgroup = "GitHub",
    inputs = [InputSlot(name = "event", kind = SlotKind.OBJECT, type = PullRequestEvent::class)],
    outputs = [OutputSlot(name = "out", kind = SlotKind.STRING)],
)
@Serializable
@SerialName("githubExportPullRequest")
class GitHubExportPullRequestNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    override val willSuspend: Boolean = true

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val event = GitHubExportPullRequestNodeSerializer.deserialize(context, inputs).event
        return GitHubExportPullRequestNodeSerializer.serialize(provide<GitHubSyncService>().synchronizePullRequest(event).name)
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val event = GitHubExportPullRequestNodeSerializer.deserializePartial(context, inputs).event
        context.trace?.actions?.set(id, buildJsonObject {
            put("action", "githubExportPullRequest")
            put("repositoryId", event?.repositoryId?.toString())
            put("pullRequestId", event?.pullRequestId?.toString())
        })
        return GitHubExportPullRequestNodeSerializer.serialize("PREVIEW")
    }
}
