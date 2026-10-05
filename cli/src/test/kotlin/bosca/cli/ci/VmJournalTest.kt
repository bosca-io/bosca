package bosca.cli.ci

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VmJournalTest {

    private lateinit var tmpDir: File
    private lateinit var file: File
    private lateinit var journal: VmJournal

    @BeforeTest
    fun setup() {
        tmpDir = Files.createTempDirectory("vm-journal-test").toFile()
        file = File(tmpDir, "vms.log")
        journal = VmJournal(file)
    }

    @AfterTest
    fun teardown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `replay on missing file returns empty list`() {
        assertTrue(journal.replay().isEmpty())
    }

    @Test
    fun `appendProvisioned then replay returns the VM`() {
        val vm = VmInstance(dropletId = "drop-1", ephemeralAgentId = "agent-1", jobId = "job-1")
        journal.appendProvisioned(vm)

        val replayed = journal.replay()

        assertEquals(1, replayed.size)
        assertEquals(vm, replayed[0])
    }

    @Test
    fun `destroyed event cancels a prior provisioned event`() {
        journal.appendProvisioned(VmInstance("drop-1", "agent-1", "job-1"))
        journal.appendDestroyed("drop-1")

        assertTrue(journal.replay().isEmpty())
    }

    @Test
    fun `replay preserves provision order and ignores destroyed entries`() {
        journal.appendProvisioned(VmInstance("drop-1", "agent-1", "job-1"))
        journal.appendProvisioned(VmInstance("drop-2", "agent-2", "job-2"))
        journal.appendProvisioned(VmInstance("drop-3", "agent-3", "job-3"))
        journal.appendDestroyed("drop-2")

        val replayed = journal.replay().map { it.dropletId }
        assertContentEquals(listOf("drop-1", "drop-3"), replayed)
    }

    @Test
    fun `malformed lines are skipped with a warning, not crash`() {
        journal.appendProvisioned(VmInstance("drop-1", "agent-1", "job-1"))
        file.appendText("not valid json\n")
        file.appendText("{\"event\":\"provisioned\"}\n")  // missing required dropletId
        journal.appendProvisioned(VmInstance("drop-2", "agent-2", "job-2"))

        val replayed = journal.replay().map { it.dropletId }
        assertContentEquals(listOf("drop-1", "drop-2"), replayed)
    }

    @Test
    fun `provisioned entry without ephemeralAgentId or jobId is dropped from replay`() {
        file.writeText("""{"event":"provisioned","ts":1,"dropletId":"drop-1"}""" + "\n")

        assertTrue(journal.replay().isEmpty())
    }

    @Test
    fun `empty lines in the file are tolerated`() {
        journal.appendProvisioned(VmInstance("drop-1", "agent-1", "job-1"))
        file.appendText("\n\n")
        journal.appendProvisioned(VmInstance("drop-2", "agent-2", "job-2"))

        val replayed = journal.replay().map { it.dropletId }
        assertContentEquals(listOf("drop-1", "drop-2"), replayed)
    }

    @Test
    fun `compact truncates the file to empty`() {
        journal.appendProvisioned(VmInstance("drop-1", "agent-1", "job-1"))
        journal.appendDestroyed("drop-1")
        assertTrue(file.length() > 0)

        journal.compact()

        assertEquals(0L, file.length())
        assertTrue(journal.replay().isEmpty())
    }

    @Test
    fun `compact on missing file is a no-op`() {
        assertTrue(!file.exists())
        journal.compact()
        assertTrue(!file.exists())
    }

    @Test
    fun `appendProvisioned creates parent directories as needed`() {
        val deepFile = File(tmpDir, "a/b/c/vms.log")
        val deepJournal = VmJournal(deepFile)

        deepJournal.appendProvisioned(VmInstance("drop-1", "agent-1", "job-1"))

        assertTrue(deepFile.exists())
        assertEquals(1, deepJournal.replay().size)
    }

    @Test
    fun `re-provisioning the same dropletId overwrites the prior entry`() {
        journal.appendProvisioned(VmInstance("drop-1", "agent-1", "job-1"))
        journal.appendProvisioned(VmInstance("drop-1", "agent-1b", "job-1b"))

        val replayed = journal.replay()
        assertEquals(1, replayed.size)
        assertEquals("agent-1b", replayed[0].ephemeralAgentId)
        assertEquals("job-1b", replayed[0].jobId)
    }
}
