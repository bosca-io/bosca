package bosca.ai.kit.tools.pipeline

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Deterministic, layered layout for model-authored graphs. The model never supplies coordinates. */
internal object PipelineGraphLayout {
    private const val COLUMN_GAP = 320.0
    private const val ROW_GAP = 180.0

    fun layout(graph: JsonElement): JsonElement {
        val root = graph as? JsonObject ?: return graph
        val nodes = root["nodes"] as? JsonArray ?: return graph
        val edges = root["edges"] as? JsonArray ?: JsonArray(emptyList())
        val nodeObjects = nodes.mapNotNull { it as? JsonObject }
        if (nodeObjects.size != nodes.size) return graph

        val ids = nodeObjects.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }.toSet()
        if (ids.size != nodeObjects.size) return graph

        val connections = edges.mapNotNull { element ->
            val edge = element as? JsonObject ?: return@mapNotNull null
            val source = edge["source"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val target = edge["target"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (source !in ids || target !in ids) null else source to target
        }.sortedWith(compareBy({ it.first }, { it.second }))

        // Longest distance from any root. A valid graph is acyclic; validation runs before layout.
        val rank = ids.associateWith { 0 }.toMutableMap()
        repeat((ids.size - 1).coerceAtLeast(0)) {
            var changed = false
            for ((source, target) in connections) {
                val candidate = rank.getValue(source) + 1
                if (candidate > rank.getValue(target)) {
                    rank[target] = candidate
                    changed = true
                }
            }
            if (!changed) return@repeat
        }

        val parents = connections.groupBy({ it.second }, { it.first })
        val positions = mutableMapOf<String, Pair<Double, Double>>()
        rank.entries.groupBy({ it.value }, { it.key }).toSortedMap().forEach { (column, columnIds) ->
            columnIds.sortedWith(compareBy<String>({ parents[it].orEmpty().sorted().joinToString("|") }, { it }))
                .forEachIndexed { row, id -> positions[id] = column * COLUMN_GAP to row * ROW_GAP }
        }

        val laidOut = nodeObjects.map { node ->
            val id = node.getValue("id").jsonPrimitive.content
            val (x, y) = positions.getValue(id)
            JsonObject(node.toMutableMap().apply {
                put("position", buildJsonObject {
                    put("x", JsonPrimitive(x))
                    put("y", JsonPrimitive(y))
                })
            })
        }
        return JsonObject(root.toMutableMap().apply { put("nodes", JsonArray(laidOut)) })
    }
}
