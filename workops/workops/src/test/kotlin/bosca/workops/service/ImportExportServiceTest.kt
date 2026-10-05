package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.PendingPhaseImplementationException
import bosca.workops.model.task.Task
import bosca.workops.repository.AuditRetentionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImportExportServiceTest {
    private val tasks = mockk<TaskService>()
    private val importer = ImporterServiceImpl(tasks)

    @Test
    fun `csv preview filters blanks limits samples and infers supported header aliases`() = runTest {
        assertEquals(ImportPreview("CSV", emptyList(), emptyList()), importer.preview("csv", "\n  \n"))

        val headers = " Summary ,TITLE,name,description,body,assignee,Assigned To,priority,unknown"
        val rows = (1..12).joinToString("\n") { "$it,t,n,d,b,a,aa,p,u" }
        val preview = importer.preview("CsV", "$headers\n\n$rows")

        assertEquals(
            listOf("summary", "summary", "summary", "description", "description", "assigneeProfileId", "assigneeProfileId", "priority", null),
            preview.columns.map { it.inferredField },
        )
        assertEquals((0..8).toList(), preview.columns.map { it.index })
        assertEquals(10, preview.sampleRows.size)
        assertEquals("1", preview.sampleRows.first().first())
        assertEquals("10", preview.sampleRows.last().first())
    }

    @Test
    fun `csv parser handles quoted commas escaped quotes whitespace and trailing cells`() {
        assertEquals(
            listOf("alpha", "two, values", "say \"hello\"", "", "unterminated"),
            CsvImporter.parseLine(" alpha ,\"two, values\",\"say \"\"hello\"\"\",,\"unterminated"),
        )
        assertEquals(listOf(""), CsvImporter.parseLine(""))
    }

    @Test
    fun `csv commit imports mapped rows skips absent summaries and records row failures`() = runTest {
        val projectId = UUID.random()
        val principalId = UUID.random()
        val profileId = UUID.random()
        val source = "title,body\nGood,Details\n,Blank\nFailure,Bad\nNo message,Worse\nOnly title"
        coEvery { tasks.create(any(), principalId, profileId, profileId) } answers {
            when (firstArg<bosca.workops.model.task.CreateTaskInput>().summary) {
                "Failure" -> throw IllegalArgumentException("rejected")
                "No message" -> throw Exception()
                else -> task(summary = firstArg<bosca.workops.model.task.CreateTaskInput>().summary)
            }
        }

        val result = importer.commit(
            "CSV",
            source,
            projectId,
            mapOf(0 to "summary", 1 to "description"),
            principalId,
            profileId,
            profileId,
        )

        assertEquals(2, result.imported)
        assertEquals(1, result.skipped)
        assertEquals(listOf("row 4: rejected", "row 5: unknown"), result.errors)
        coVerify(exactly = 1) {
            tasks.create(
                match { it.summary == "Good" && it.descriptionMarkdown == "Details" && it.projectId == projectId },
                principalId,
                profileId,
                profileId,
            )
        }
        coVerify(exactly = 1) {
            tasks.create(
                match { it.summary == "Only title" && it.descriptionMarkdown == null },
                principalId,
                profileId,
                profileId,
            )
        }
    }

    @Test
    fun `csv commit handles empty and unmapped inputs and preserves cancellation`() = runTest {
        val id = UUID.random()
        assertEquals(ImportResult(0, 0, emptyList()), importer.commit("CSV", "\n", id, emptyMap(), id, null, id))
        assertEquals(
            ImportResult(0, 2, emptyList()),
            importer.commit("CSV", "title\nFirst\nSecond", id, mapOf(3 to "summary"), id, null, id),
        )

        coEvery { tasks.create(any(), id, null, id) } throws CancellationException("stop")
        assertFailsWith<CancellationException> {
            importer.commit("CSV", "title\nFirst", id, mapOf(0 to "summary"), id, null, id)
        }
    }

    @Test
    fun `import format dispatch rejects deferred and unknown formats`() = runTest {
        for (format in listOf("JIRA_XML", "github_json")) {
            assertFailsWith<PendingPhaseImplementationException> { importer.preview(format, "value") }
            assertFailsWith<PendingPhaseImplementationException> {
                importer.commit(format, "value", UUID.random(), emptyMap(), UUID.random(), null, UUID.random())
            }
        }
        assertFailsWith<IllegalArgumentException> { importer.preview("yaml", "value") }
        assertFailsWith<IllegalArgumentException> {
            importer.commit("yaml", "value", UUID.random(), emptyMap(), UUID.random(), null, UUID.random())
        }
    }

    @Test
    fun `task export quotes special fields and emits nullable assignee as empty`() = runTest {
        val query = mockk<TaskQueryService>()
        val exporter = TaskExportServiceImpl(query)
        val first = task(summary = "Comma, quote \" and\nnewline")
        val second = task(summary = "Plain").copy(key = "", assigneeProfileId = UUID.random())
        val quoteOnly = task(summary = "A \"quoted\" value")
        val newlineOnly = task(summary = "First line\nSecond line")
        coEvery { query.search("project = WORK", null, 0, 500) } returns
            TaskSearchResult(listOf(first, second, quoteOnly, newlineOnly), emptyList())

        val csv = exporter.exportCsv("project = WORK", null).decodeToString()

        assertTrue(csv.startsWith("key,summary,statusId,priorityId,assigneeProfileId,createdAt\n"))
        assertTrue("\"Comma, quote \"\" and\nnewline\"" in csv)
        assertTrue("\"A \"\"quoted\"\" value\"" in csv)
        assertTrue("\"First line\nSecond line\"" in csv)
        assertTrue(",,${first.createdAt}" in csv)
        assertTrue(second.assigneeProfileId.toString() in csv)
    }

    @Test
    fun `task export advances through full pages until a short page`() = runTest {
        val query = mockk<TaskQueryService>()
        val exporter = TaskExportServiceImpl(query)
        val row = task(summary = "Page")
        coEvery { query.search("", null, 0, 500) } returns TaskSearchResult(List(500) { row }, emptyList())
        coEvery { query.search("", null, 500, 500) } returns TaskSearchResult(emptyList(), emptyList())

        val lines = exporter.exportCsv("", null).decodeToString().lineSequence().filter { it.isNotEmpty() }.count()

        assertEquals(501, lines)
        coVerify(exactly = 1) { query.search("", null, 500, 500) }
    }

    @Test
    fun `audit retention detaches only valid partitions older than cutoff`() = runTest {
        val repository = mockk<AuditRetentionRepository>(relaxed = true)
        val service = AuditRetentionServiceImpl(repository)
        val old = java.time.YearMonth.now().minusMonths(13).toString().replace("-", "")
        val current = java.time.YearMonth.now().toString().replace("-", "")
        val oldPartition = "task_history_$old"
        coEvery { repository.listPartitions() } returns listOf(
            "other_202001",
            "task_history_20201",
            "task_history_ABCD01",
            "task_history_2020AA",
            "task_history_202013",
            oldPartition,
            "task_history_$current",
        )

        assertEquals(listOf(oldPartition), service.rotate(12))
        coVerify(exactly = 1) { repository.detach(oldPartition) }
        coVerify(exactly = 0) { repository.detach("task_history_$current") }
    }

    private fun task(summary: String) = Task(
        id = UUID.random(),
        key = "WORK-${UUID.random()}",
        projectId = UUID.random(),
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = summary,
        reporterProfileId = UUID.random(),
        createdAt = OffsetDateTime.parse("2026-08-01T10:00:00Z"),
        createdByPrincipalId = UUID.random(),
        modifiedByPrincipalId = UUID.random(),
    )
}
