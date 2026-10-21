package bosca.cli.backup

import bosca.backup.BackupObject
import bosca.backup.ObjectBackupManifest
import bosca.backup.PostgresBackupContentInspectionSummary
import bosca.backup.PostgresBackupContentInventory
import bosca.backup.PostgresBackupContentReport
import bosca.backup.PostgresBackupContentSelectionStatus
import bosca.backup.PostgresBackupContentStatus
import bosca.backup.PostgresBackupManifest
import bosca.cli.BoscaCommand
import bosca.cli.config.CliConfigStore
import bosca.cli.config.CliInvocation
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupInspectCommandTest {
    private lateinit var directory: Path
    private val database = PostgresBackupManifest(
        "capture", "old-instance", "bosca", "2026-10-18T00:00:00Z", "2026-10-18T00:01:00Z",
        "exported", "1:2:", "18.0", 180000, "UTF8", mapOf("vector" to "0.8.1"), 128, "a".repeat(64),
        contentInventory = PostgresBackupContentInventory(2, 128, "b".repeat(64)),
    )
    private val objects = ObjectBackupManifest(
        "capture", "old-instance", "content", database.created,
        listOf(BackupObject("content", "metadata/id/7/content", "observed", 5, "c".repeat(64), "assets/image")),
    )
    private val report = PostgresBackupContentReport(
        "snapshots/databases/capture", database, objects,
        PostgresBackupContentSelectionStatus.entries.associateWith {
            if (it == PostgresBackupContentSelectionStatus.SELECTED) 2L else 0L
        },
        PostgresBackupContentInspectionSummary(
            database.id, objects.id, "content", PostgresBackupContentStatus.entries.associateWith {
                if (it == PostgresBackupContentStatus.VERIFIED) 2L else 0L
            },
        ),
    )

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("backup-inspect-cli-")
        CliConfigStore.directoryOverride = directory.resolve("unreadable-config").toFile().apply {
            mkdirs()
            resolve("config.json").writeText("this is not JSON")
        }
        CliConfigStore.workingDirectoryOverride = directory.toFile()
        CliInvocation.selectProfile(null)
    }

    @AfterTest
    fun tearDown() {
        CliConfigStore.directoryOverride = null
        CliConfigStore.workingDirectoryOverride = null
        CliInvocation.selectProfile(null)
        directory.toFile().deleteRecursively()
    }

    private fun cli() = BoscaCommand().subcommands(BackupCommand())

    private fun writeReport(value: PostgresBackupContentReport = report): Path =
        directory.resolve("content-report.json").also {
            Files.writeString(it, Json.encodeToString(PostgresBackupContentReport.serializer(), value))
        }

    @Test
    fun `offline inspection shows identities retained paths and every recorded outcome without reading configuration`() {
        val path = writeReport()
        val before = Files.readAllBytes(path).toList()

        val result = cli().test(listOf("--profile", "missing", "backup", "inspect", "--report", path.toString()))

        assertEquals(0, result.statusCode, result.output)
        for (expected in listOf(
            "Capture: capture", "Source: old-instance", "Database: bosca", "PostgreSQL: 18.0", "Encoding: UTF8",
            "Database artifact: snapshots/databases/capture", "Dump: database.dump", "Dump bytes: 128",
            "SHA-256: ${database.sha256}", "References: 2", "Declared assets: 1", "Store: content", "Scope: content",
            "Recorded reference gaps: 0", "SELECTED: 2", "VERIFIED: 2",
        )) assertTrue(expected in result.stdout, result.output)
        for (status in PostgresBackupContentStatus.entries.filter { it != PostgresBackupContentStatus.VERIFIED }) {
            assertTrue("${status.name}: 0" in result.stdout, result.output)
        }
        assertTrue("Recorded evidence only" in result.stderr, result.output)
        assertTrue("Stored bytes and recovery readiness have not been checked" in result.stderr, result.output)
        assertEquals(before, Files.readAllBytes(path).toList())
        assertEquals(setOf("content-report.json", "unreadable-config"), directory.toFile().list()?.toSet())
    }

    @Test
    fun `json output is the original validated portable model with its required format headers`() {
        val path = writeReport()
        val result = cli().test(listOf("backup", "inspect", "--report", path.toString(), "--json"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(report, Json.decodeFromString(PostgresBackupContentReport.serializer(), result.stdout))
        assertEquals(Json.parseToJsonElement(Files.readString(path)), Json.parseToJsonElement(result.stdout))
        assertTrue("Recorded evidence only" in result.stderr, result.output)
        assertFalse("Recorded evidence only" in result.stdout)
    }

    @Test
    fun `every recorded inspection gap remains visible and returns a partial result`() {
        for (status in PostgresBackupContentStatus.entries.filter { it != PostgresBackupContentStatus.VERIFIED }) {
            val gaps = report.copy(inspection = report.inspection.copy(counts = report.inspection.counts + mapOf(
                PostgresBackupContentStatus.VERIFIED to 1L, status to 1L,
            )))
            val result = cli().test(listOf("backup", "inspect", "--report", writeReport(gaps).toString()))

            assertEquals(2, result.statusCode, result.output)
            assertTrue("Recorded reference gaps: 1" in result.stdout, result.output)
            assertTrue("${status.name}: 1" in result.stdout, result.output)
            assertTrue("Recorded evidence only" in result.stderr, result.output)
        }
    }

    @Test
    fun `partial json keeps all recorded gaps and returns nonzero without polluting stdout`() {
        val gaps = report.copy(inspection = report.inspection.copy(counts = report.inspection.counts + mapOf(
            PostgresBackupContentStatus.VERIFIED to 0L, PostgresBackupContentStatus.NOT_RETAINED to 2L,
        )))
        val result = cli().test(listOf("backup", "inspect", "--report", writeReport(gaps).toString(), "--json"))

        assertEquals(2, result.statusCode, result.output)
        assertEquals(gaps, Json.decodeFromString(PostgresBackupContentReport.serializer(), result.stdout))
        assertTrue("Recorded reference gaps: 2" in result.stderr, result.output)
    }

    @Test
    fun `empty reference inventory is inspectable with no inferred recovery readiness`() {
        val empty = report.copy(
            database = database.copy(contentInventory = database.contentInventory?.copy(count = 0)),
            objects = objects.copy(objects = emptyList()), selectionCounts = report.selectionCounts.mapValues { 0L },
            inspection = report.inspection.copy(counts = report.inspection.counts.mapValues { 0L }),
        )
        val result = cli().test(listOf("backup", "inspect", "--report", writeReport(empty).toString()))

        assertEquals(0, result.statusCode, result.output)
        assertTrue("References: 0" in result.stdout, result.output)
        assertTrue("Declared assets: 0" in result.stdout, result.output)
        assertTrue("recovery readiness have not been checked" in result.stderr, result.output)
    }

    @Test
    fun `all selection outcomes are printed separately from inspection outcomes`() {
        for (status in PostgresBackupContentSelectionStatus.entries.filter { it != PostgresBackupContentSelectionStatus.SELECTED }) {
            val selection = report.copy(selectionCounts = report.selectionCounts + mapOf(
                PostgresBackupContentSelectionStatus.SELECTED to 1L, status to 1L,
            ))
            val result = cli().test(listOf("backup", "inspect", "--report", writeReport(selection).toString()))

            assertEquals(0, result.statusCode, result.output)
            assertTrue("Recorded selection" in result.stdout, result.output)
            assertTrue("${status.name}: 1" in result.stdout, result.output)
            assertTrue("Recorded inspection" in result.stdout, result.output)
            assertTrue("VERIFIED: 2" in result.stdout, result.output)
        }
    }

    @Test
    fun `invalid report invariants fail without emitting a portable report or changing input`() {
        val invalidReports = listOf(
            report.copy(version = 2), report.copy(format = "other"),
            report.copy(databasePath = "../outside"), report.copy(database = database.copy(version = 2)),
            report.copy(objects = objects.copy(id = "different")),
            report.copy(inspection = report.inspection.copy(store = "different")),
            report.copy(selectionCounts = report.selectionCounts - PostgresBackupContentSelectionStatus.MISSING),
            report.copy(inspection = report.inspection.copy(counts = report.inspection.counts +
                (PostgresBackupContentStatus.VERIFIED to 1L))),
            report.copy(inspection = report.inspection.copy(counts = report.inspection.counts +
                (PostgresBackupContentStatus.MISSING to Long.MAX_VALUE))),
        )
        for (invalid in invalidReports) {
            val path = writeReport(invalid)
            val before = Files.readString(path)
            val result = cli().test(listOf("backup", "inspect", "--report", path.toString(), "--json"))

            assertEquals(1, result.statusCode, result.output)
            assertEquals("", result.stdout)
            assertTrue("Cannot inspect recovery report" in result.stderr, result.output)
            assertEquals(before, Files.readString(path))
        }
    }

    @Test
    fun `malformed json missing required headers and unknown enum values fail visibly`() {
        val encoded = Json.parseToJsonElement(Json.encodeToString(PostgresBackupContentReport.serializer(), report)).jsonObject
        val malformed = listOf("not JSON", "null", "[]", "{}", "{\"format\":") +
            listOf("version", "format").map { JsonObject(encoded - it).toString() } +
            encoded.toString().replace("VERIFIED", "UNSUPPORTED_STATUS")
        for (body in malformed) {
            val path = directory.resolve("content-report.json").also { Files.writeString(it, body) }
            val result = cli().test(listOf("backup", "inspect", "--report", path.toString(), "--json"))

            assertEquals(1, result.statusCode, result.output)
            assertEquals("", result.stdout)
            assertTrue("Cannot inspect recovery report" in result.stderr, result.output)
            assertEquals(body, Files.readString(path))
        }
    }

    @Test
    fun `missing and directory inputs fail without creating output or reading CLI configuration`() {
        for (path in listOf(directory.resolve("missing.json"), directory)) {
            val result = cli().test(listOf("backup", "inspect", "--report", path.toString()))

            assertEquals(1, result.statusCode, result.output)
            assertEquals("", result.stdout)
            assertTrue("Cannot inspect recovery report" in result.stderr, result.output)
        }
        assertFalse(Files.exists(directory.resolve("missing.json")))
    }

    @Test
    fun `command help explains local evidence and JSON output without requiring authentication`() {
        val root = cli().test("--help")
        val group = cli().test("backup --help")
        val result = cli().test("backup inspect --help")

        assertEquals(0, root.statusCode, root.output)
        assertTrue("backup" in root.stdout, root.output)
        assertEquals(0, group.statusCode, group.output)
        assertTrue("inspect" in group.stdout, group.output)
        assertEquals(0, result.statusCode, result.output)
        for (option in listOf("--report", "--json", "local recovery report", "does not verify stored bytes")) {
            assertTrue(option in result.stdout.replace("\n", " ").replace(Regex(" +"), " "), result.output)
        }
    }
}
