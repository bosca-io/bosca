package bosca.backup

import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Retains recorded evidence in a caller-owned prefix without asserting recovery completion. */
internal class PostgresBackupContentReportStore(
    private val storage: ObjectStorageService,
    private val json: Json,
    private val prefix: String,
) {
    init {
        require(prefix.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Invalid content report prefix: $prefix"
        }
    }

    suspend fun upload(report: PostgresBackupContentReport): PostgresBackupContentReport {
        validate(report)
        val publishedText = try {
            readText("report.json")
        } catch (_: ObjectNotFoundException) {
            null
        }
        var partialReport: SerializationException? = null
        val published = try {
            publishedText?.let { decode(it) }
        } catch (failure: SerializationException) {
            partialReport = failure
            null
        }
        val intent = try {
            read("intent.json")
        } catch (_: ObjectNotFoundException) {
            null
        }
        require(published == null || published == report) { "Conflicting retained content report: $prefix" }
        require(intent == null || intent == report) { "Conflicting content report intent: $prefix" }
        if (intent == null && partialReport != null) throw partialReport
        if (published == null) {
            if (intent == null) write("intent.json", report)
            write("report.json", report)
        }
        return report
    }

    suspend fun load(): PostgresBackupContentReport = read("report.json")

    private fun validate(report: PostgresBackupContentReport) {
        report.validate()
        val components = listOfNotNull(
            report.database.payloadPath, report.database.contentInventory?.path, "manifest.json", "intent.json",
        ).map { "${report.databasePath}/$it" }.toSet()
        val paths = setOf("$prefix/intent.json", "$prefix/report.json")
        require(paths.none { it in components } && report.objects.objects.none { it.payloadPath in paths }) {
            "Content report path conflicts with a declared database or asset component"
        }
    }

    private fun decode(text: String): PostgresBackupContentReport =
        json.decodeFromString(PostgresBackupContentReport.serializer(), text).also { validate(it) }

    private suspend fun read(name: String): PostgresBackupContentReport = decode(readText(name))

    private suspend fun readText(name: String): String {
        currentCoroutineContext().ensureActive()
        return storage.getInputStream(StringObjectPath("$prefix/$name")).bufferedReader().use { it.readText() }
    }

    private suspend fun write(name: String, report: PostgresBackupContentReport) {
        currentCoroutineContext().ensureActive()
        val bytes = json.encodeToString(PostgresBackupContentReport.serializer(), report).toByteArray()
        val written = bytes.inputStream().use {
            storage.setInputStream(StringObjectPath("$prefix/$name"), it, bytes.size.toLong())
        }
        check(written == bytes.size.toLong()) { "Incomplete content report write: $name" }
        check(read(name) == report) { "Retained content report differs from the selected evidence: $name" }
    }
}
