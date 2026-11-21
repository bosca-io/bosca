package bosca.workops.ui

import kotlinx.serialization.Serializable

/** A connected promotion family; its ordered stops do not imply edges between unrelated siblings. */
@Serializable
data class WorkOpsPromotionFamily(
    val id: String,
    val environments: List<WorkOpsEnvironmentRecord>,
    val cyclic: Boolean = false,
) {
    val label: String get() {
        val channels = environments.map { when (it.targetType) {
            "PLAY_TRACK" -> "Google Play"
            "TESTFLIGHT", "APP_STORE" -> "App Store"
            else -> "Infrastructure"
        } }.distinct()
        return channels.singleOrNull() ?: "Environments"
    }
}

/** Connected components stay separate; Kahn ordering honors every source before its target. */
internal fun workOpsPromotionFamilies(environments: List<WorkOpsEnvironmentRecord>): List<WorkOpsPromotionFamily> {
    val byId = environments.associateBy { it.id }
    val position = environments.mapIndexed { index, row -> row.id to index }.toMap()
    val comparator = compareBy<WorkOpsEnvironmentRecord> { it.order }.thenBy { position[it.id] }
    val neighbors = environments.associate { it.id to mutableSetOf<String>() }
    for (row in environments) for (source in row.sources.distinct().filter { it in byId }) {
        neighbors.getValue(row.id).add(source); neighbors.getValue(source).add(row.id)
    }
    val seen = mutableSetOf<String>()
    val families = mutableListOf<WorkOpsPromotionFamily>()
    for (row in environments) {
        if (row.id in seen) continue
        val members = linkedSetOf<String>(); val queue = ArrayDeque<String>(); queue.add(row.id)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!members.add(id)) continue
            seen.add(id); queue.addAll(neighbors.getValue(id))
        }
        val remaining = members.associateWith { id -> byId.getValue(id).sources.distinct().count { it in members } }.toMutableMap()
        val ready = members.filter { remaining[it] == 0 }.map { byId.getValue(it) }.sortedWith(comparator).toMutableList()
        val ordered = mutableListOf<WorkOpsEnvironmentRecord>()
        while (ready.isNotEmpty()) {
            val next = ready.removeAt(0); ordered.add(next)
            for (target in environments.filter { it.id in members && next.id in it.sources }) {
                val count = remaining.getValue(target.id) - 1; remaining[target.id] = count
                if (count == 0) { ready.add(target); ready.sortWith(comparator) }
            }
        }
        val cyclic = ordered.size < members.size
        ordered.addAll(members.filter { id -> ordered.none { it.id == id } }.map { byId.getValue(it) }.sortedWith(comparator))
        families.add(WorkOpsPromotionFamily(members.sorted().first(), ordered, cyclic))
    }
    return families.sortedWith(compareBy<WorkOpsPromotionFamily> { it.environments.minOf { row -> row.order } }
        .thenBy { it.environments.minOf { row -> position.getValue(row.id) } })
}
