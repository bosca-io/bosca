package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.PendingPhaseImplementationException
import bosca.workops.model.task.CreateTaskInput

// ----- Import -----------------------------------------------------

@ServiceImplementation
class ImporterServiceImpl(
    private val taskService: TaskService,
) : ImporterService {

    override suspend fun preview(format: String, source: String): ImportPreview = when (format.uppercase()) {
        "CSV" -> CsvImporter.preview(source)
        "JIRA_XML", "GITHUB_JSON" -> throw PendingPhaseImplementationException(
            variant = "Importer($format)", owningPhase = 18,
        )

        else -> throw IllegalArgumentException("unknown import format: $format")
    }

    override suspend fun commit(
        format: String,
        source: String,
        projectId: UUID,
        mapping: Map<Int, String>,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        reporterProfileId: UUID,
    ): ImportResult = when (format.uppercase()) {
        "CSV" -> CsvImporter.commit(
            source, projectId, mapping, taskService,
            actingPrincipalId, actingProfileId, reporterProfileId
        )

        "JIRA_XML", "GITHUB_JSON" -> throw PendingPhaseImplementationException(
            variant = "Importer($format)", owningPhase = 18,
        )

        else -> throw IllegalArgumentException("unknown import format: $format")
    }
}

/**
 * Minimal RFC 4180-ish CSV importer. Handles quoted fields and
 * commas-inside-quotes. Multi-line quoted strings aren't supported;
 * Phase 18 swaps in OpenCSV.
 */
object CsvImporter {

    fun preview(source: String): ImportPreview {
        val lines = source.lineSequence().filter { it.isNotBlank() }.take(11).toList()
        if (lines.isEmpty()) return ImportPreview("CSV", emptyList(), emptyList())
        val headers = parseLine(lines.first())
        val columns = headers.mapIndexed { idx, h ->
            ImportColumn(
                index = idx, header = h,
                inferredField = inferField(h),
            )
        }
        val samples = lines.drop(1).map { parseLine(it) }
        return ImportPreview("CSV", columns, samples)
    }

    suspend fun commit(
        source: String,
        projectId: UUID,
        mapping: Map<Int, String>,
        taskService: TaskService,
        actingPrincipalId: UUID,
        actingProfileId: UUID?,
        reporterProfileId: UUID,
    ): ImportResult {
        val lines = source.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return ImportResult(0, 0, emptyList())
        val rows = lines.drop(1).map { parseLine(it) }
        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        for ((rowIdx, row) in rows.withIndex()) {
            val summary = mapping.entries.firstOrNull { it.value == "summary" }
                ?.let { row.getOrNull(it.key) }
                ?.takeIf { it.isNotBlank() }
            if (summary == null) {
                skipped++
                continue
            }
            val description = mapping.entries.firstOrNull { it.value == "description" }
                ?.let { row.getOrNull(it.key) }
            try {
                taskService.create(
                    input = CreateTaskInput(
                        projectId = projectId,
                        summary = summary,
                        descriptionMarkdown = description,
                    ),
                    actingPrincipalId = actingPrincipalId,
                    actingProfileId = actingProfileId,
                    reporterProfileId = reporterProfileId,
                )
                imported++
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.add("row ${rowIdx + 2}: ${e.message ?: "unknown"}")
            }
        }
        return ImportResult(imported, skipped, errors)
    }

    private fun inferField(header: String): String? = when (header.lowercase().trim()) {
        "summary", "title", "name" -> "summary"
        "description", "body" -> "description"
        "assignee", "assigned to" -> "assigneeProfileId"
        "priority" -> "priority"
        else -> null
    }

    fun parseLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    // Escaped double-quote.
                    sb.append('"'); i += 2; continue
                }

                c == '"' -> {
                    inQuotes = !inQuotes; i++; continue
                }

                c == ',' && !inQuotes -> {
                    out.add(sb.toString()); sb.setLength(0); i++; continue
                }

                else -> {
                    sb.append(c); i++
                }
            }
        }
        out.add(sb.toString())
        return out.map { it.trim() }
    }
}

// ----- Export ------------------------------------------------------

@ServiceImplementation
class TaskExportServiceImpl(
    private val taskQueryService: TaskQueryService,
) : TaskExportService {

    override suspend fun exportCsv(bql: String, actingProfileId: UUID?): ByteArray {
        val out = StringBuilder()
        out.append("key,summary,statusId,priorityId,assigneeProfileId,createdAt\n")
        val pageSize = 500
        var offset = 0L
        while (true) {
            val page = taskQueryService.search(bql, actingProfileId, offset = offset, limit = pageSize).rows
            for (row in page) {
                out.append(escape(row.key)).append(',')
                    .append(escape(row.summary)).append(',')
                    .append(row.statusId.toString()).append(',')
                    .append(row.priorityId.toString()).append(',')
                    .append(row.assigneeProfileId?.toString().orEmpty()).append(',')
                    .append(row.createdAt.toString())
                    .append('\n')
            }
            if (page.size < pageSize) break
            offset += pageSize
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun escape(s: String): String {
        if (s.isEmpty()) return ""
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            return "\"${s.replace("\"", "\"\"")}\""
        }
        return s
    }
}

// ----- Audit retention ---------------------------------------------

@ServiceImplementation
class AuditRetentionServiceImpl(
    private val repository: bosca.workops.repository.AuditRetentionRepository,
) : AuditRetentionService {

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(AuditRetentionServiceImpl::class.java)
        private const val PARTITION_PREFIX = "task_history_"
    }

    override suspend fun rotate(retentionMonths: Int): List<String> {
        val cutoffYearMonth = java.time.YearMonth.now().minusMonths(retentionMonths.toLong())
        // The partitions are named workops.task_history_YYYYMM by V2.
        val partitions = repository.listPartitions()
        val detached = mutableListOf<String>()
        for (partition in partitions) {
            if (!partition.startsWith(PARTITION_PREFIX)) {
                log.warn("Audit retention: partition '{}' does not match expected 'task_history_YYYYMM' format, skipping", partition)
                continue
            }
            val suffix = partition.substring(PARTITION_PREFIX.length)
            if (suffix.length != 6) {
                log.warn("Audit retention: partition '{}' does not match expected 'task_history_YYYYMM' format, skipping", partition)
                continue
            }
            val year = suffix.substring(0, 4).toIntOrNull()
            if (year == null) {
                log.warn("Audit retention: partition '{}' has non-numeric year component '{}', skipping", partition, suffix.substring(0, 4))
                continue
            }
            val monthText = suffix.substring(4, 6)
            val month = monthText.toIntOrNull()
            if (month == null || month !in 1..12) {
                log.warn("Audit retention: partition '{}' has invalid month component '{}', skipping", partition, monthText)
                continue
            }
            val partitionYm = java.time.YearMonth.of(year, month)
            if (partitionYm.isBefore(cutoffYearMonth)) {
                repository.detach(partition)
                detached.add(partition)
            }
        }
        return detached
    }
}
