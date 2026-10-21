package bosca.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostgresBackupContentReportTest {
    private val database = PostgresBackupManifest(
        "capture", "source", "bosca", "2026-10-18T00:00:00Z", "2026-10-18T00:01:00Z",
        "exported", "1:2:", "18", 180000, "UTF8", emptyMap(), 128, "a".repeat(64),
        contentInventory = PostgresBackupContentInventory(2, 128, "b".repeat(64)),
    )
    private val objects = ObjectBackupManifest("capture", "source", "content", database.created, listOf(
        BackupObject("content", "metadata/id/7/content", "observed", 5, "c".repeat(64), "assets/image"),
    ))
    private val selection = PostgresBackupContentSelectionStatus.entries.associateWith {
        if (it == PostgresBackupContentSelectionStatus.SELECTED) 2L else 0L
    }
    private val inspection = PostgresBackupContentInspectionSummary(
        database.id, objects.id, "content", PostgresBackupContentStatus.entries.associateWith {
            if (it == PostgresBackupContentStatus.VERIFIED) 2L else 0L
        },
    )
    private val report = PostgresBackupContentReport("database/capture", database, objects, selection, inspection)

    @Test
    fun `portable evidence round trips with required headers and preserves explicit gaps`() {
        val gaps = report.copy(
            selectionCounts = selection + mapOf(PostgresBackupContentSelectionStatus.SELECTED to 1L,
                PostgresBackupContentSelectionStatus.MISSING to 1L),
            inspection = inspection.copy(counts = inspection.counts + mapOf(PostgresBackupContentStatus.VERIFIED to 1L,
                PostgresBackupContentStatus.NOT_RETAINED to 1L)),
        )
        for (value in listOf(report, gaps)) {
            value.validate()
            val encoded = Json.encodeToString(PostgresBackupContentReport.serializer(), value)
            assertEquals(value, Json.decodeFromString(PostgresBackupContentReport.serializer(), encoded))
            val fields = Json.parseToJsonElement(encoded).jsonObject
            for (header in listOf("format", "version")) {
                assertFailsWith<SerializationException> {
                    Json.decodeFromJsonElement(PostgresBackupContentReport.serializer(), JsonObject(fields - header))
                }
            }
        }
    }

    @Test
    fun `artifact identities stores and reference inventory must agree`() {
        for (invalid in listOf(
            report.copy(database = database.copy(contentInventory = null)),
            report.copy(objects = objects.copy(id = "other")),
            report.copy(objects = objects.copy(sourceId = "other")),
            report.copy(inspection = inspection.copy(databaseId = "other")),
            report.copy(inspection = inspection.copy(objectManifestId = "other")),
            report.copy(inspection = inspection.copy(store = " ")),
            report.copy(inspection = inspection.copy(store = "other")),
            report.copy(objects = objects.copy(version = 2)),
            report.copy(database = database.copy(version = 2)),
            report.copy(version = 2), report.copy(format = "other"),
        )) assertFailsWith<IllegalArgumentException> { invalid.validate() }
    }

    @Test
    fun `native location must be relative and distinct from asset payloads`() {
        for (path in listOf("", "/absolute", "../outside", "database/../capture", "database//capture", "database/")) {
            assertFailsWith<IllegalArgumentException> { report.copy(databasePath = path).validate() }
        }
        for (name in listOf("database.dump", "content-references.json", "manifest.json", "intent.json")) {
            val conflicting = objects.objects.single().copy(payloadPath = "${report.databasePath}/$name")
            assertFailsWith<IllegalArgumentException> { report.copy(objects = objects.copy(objects = listOf(conflicting))).validate() }
        }
    }

    @Test
    fun `every outcome is nonnegative and totals exactly the saved reference count without overflow`() {
        for (counts in listOf(selection - PostgresBackupContentSelectionStatus.MISSING,
            selection + (PostgresBackupContentSelectionStatus.MISSING to -1L),
            selection + (PostgresBackupContentSelectionStatus.SELECTED to 1L),
            selection + (PostgresBackupContentSelectionStatus.MISSING to Long.MAX_VALUE))) {
            assertFailsWith<IllegalArgumentException> { report.copy(selectionCounts = counts).validate() }
        }
        for (counts in listOf(inspection.counts - PostgresBackupContentStatus.MISSING,
            inspection.counts + (PostgresBackupContentStatus.MISSING to -1L),
            inspection.counts + (PostgresBackupContentStatus.VERIFIED to 1L),
            inspection.counts + (PostgresBackupContentStatus.MISSING to Long.MAX_VALUE))) {
            assertFailsWith<IllegalArgumentException> { report.copy(inspection = inspection.copy(counts = counts)).validate() }
        }
    }

    @Test
    fun `empty inventories keep all zero outcomes explicit`() {
        report.copy(database = database.copy(contentInventory = database.contentInventory?.copy(count = 0)),
            objects = objects.copy(objects = emptyList()), selectionCounts = selection.mapValues { 0L },
            inspection = inspection.copy(counts = inspection.counts.mapValues { 0L })).validate()
    }
}
