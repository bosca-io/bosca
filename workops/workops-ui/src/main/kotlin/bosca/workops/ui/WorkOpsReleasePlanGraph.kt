package bosca.workops.ui

import bosca.design.model.FlowEdge
import bosca.design.model.FlowGraph
import bosca.design.model.FlowNode
import bosca.design.model.FlowNodeData
import bosca.design.model.FlowPosition
import bosca.design.model.FlowSide
import bosca.workops.ui.graphql.WorkOpsReleasePlansData.WorkOps.CrossProject.ReleasePlans

/** The server's selected dependency depths position a read-only shared flow view. */
internal fun ReleasePlans.releaseGraph(): FlowGraph {
    val placed = mutableMapOf<Int, Int>()
    val ids = jobs.mapTo(hashSetOf()) { it.key }
    return FlowGraph(
        nodes = jobs.map { job ->
            val row = placed.getOrDefault(job.depth, 0)
            placed[job.depth] = row + 1
            FlowNode(job.key, FlowPosition(job.depth * 280.0, row * 180.0),
                data = FlowNodeData(label = job.key, description = listOfNotNull(
                    job.environment?.let { "Environment: $it" },
                    "Approval required".takeIf { job.approvalRequired },
                    job.needs.takeIf { it.isNotEmpty() }?.joinToString(", ", "Needs: "),
                    job.requirements.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                    job.steps.joinToString(" → ") { it.action }.ifBlank { "Checkpoint" },
                ).joinToString("\n")), sourcePosition = FlowSide.RIGHT, targetPosition = FlowSide.LEFT,
                draggable = false, connectable = false)
        },
        edges = jobs.flatMap { job -> job.needs.filter { it in ids }.map { dependency -> dependency to job.key } }
            .mapIndexed { index, (source, target) -> FlowEdge("dependency-$index", source, target) },
    )
}
