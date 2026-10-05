package bosca.cli.ci

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class VmJournalEntry(
    val event: String,
    val ts: Long,
    val dropletId: String,
    val ephemeralAgentId: String? = null,
    val jobId: String? = null,
) {
    companion object {
        const val PROVISIONED = "provisioned"
        const val DESTROYED = "destroyed"
    }
}

class VmJournal(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun appendProvisioned(vm: VmInstance) {
        append(
            VmJournalEntry(
                event = VmJournalEntry.PROVISIONED,
                ts = System.currentTimeMillis(),
                dropletId = vm.dropletId,
                ephemeralAgentId = vm.ephemeralAgentId,
                jobId = vm.jobId,
            )
        )
    }

    fun appendDestroyed(dropletId: String) {
        append(
            VmJournalEntry(
                event = VmJournalEntry.DESTROYED,
                ts = System.currentTimeMillis(),
                dropletId = dropletId,
            )
        )
    }

    fun replay(): List<VmInstance> {
        if (!file.exists()) return emptyList()
        val unresolved = LinkedHashMap<String, VmInstance>()
        file.readLines().forEachIndexed { i, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            val entry = try {
                json.decodeFromString(VmJournalEntry.serializer(), trimmed)
            } catch (e: Exception) {
                System.err.println("VmJournal: skipping malformed line ${i + 1}: ${e.message}")
                return@forEachIndexed
            }
            when (entry.event) {
                VmJournalEntry.PROVISIONED -> {
                    val agentId = entry.ephemeralAgentId ?: return@forEachIndexed
                    val jobId = entry.jobId ?: return@forEachIndexed
                    unresolved[entry.dropletId] = VmInstance(
                        dropletId = entry.dropletId,
                        ephemeralAgentId = agentId,
                        jobId = jobId,
                    )
                }
                VmJournalEntry.DESTROYED -> unresolved.remove(entry.dropletId)
            }
        }
        return unresolved.values.toList()
    }

    fun compact() {
        synchronized(lock) {
            if (file.exists()) file.writeText("")
        }
    }

    private fun append(entry: VmJournalEntry) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            file.appendText(json.encodeToString(VmJournalEntry.serializer(), entry) + "\n")
        }
    }

    companion object {
        fun default(): VmJournal =
            VmJournal(File(System.getProperty("user.home"), ".bosca/orchestrator/vms.log"))
    }
}
