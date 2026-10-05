package bosca.git.service

import bosca.git.model.SourceRefInput
import bosca.git.repository.QuerySourceRefRepository
import bosca.git.repository.ScriptSourceRefRepository
import bosca.serialization.UUID
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Covers [SourceRefServiceImpl]'s delegation to the two source-ref repositories. */
class SourceRefServiceImplTest {

    private val scriptRefs = mockk<ScriptSourceRefRepository>(relaxed = true)
    private val queryRefs = mockk<QuerySourceRefRepository>(relaxed = true)
    private val service = SourceRefServiceImpl(scriptRefs, queryRefs)

    private val repositoryId = UUID.random()
    private val input = SourceRefInput(repositoryId, "scripts/x/source.kts", "main")

    @Test
    fun `script source refs upsert, find, and delete through the script repository`() = runTest {
        val scriptId = UUID.random()

        service.setScriptSourceRef(scriptId, input)
        coVerify {
            scriptRefs.upsert(match {
                it.scriptId == scriptId && it.repositoryId == repositoryId &&
                    it.path == input.path && it.ref == "main"
            })
        }

        service.findScriptSourceRef(scriptId)
        coVerify { scriptRefs.findByScriptId(scriptId) }

        service.removeScriptSourceRef(scriptId)
        coVerify { scriptRefs.delete(scriptId) }

        service.findSourceRefsByRepository(repositoryId)
        coVerify { scriptRefs.findByRepository(repositoryId) }
    }

    @Test
    fun `query source refs upsert, find, and delete through the query repository`() = runTest {
        val queryId = UUID.random()

        service.setQuerySourceRef(queryId, input)
        coVerify {
            queryRefs.upsert(match {
                it.queryId == queryId && it.repositoryId == repositoryId && it.path == input.path
            })
        }

        service.findQuerySourceRef(queryId)
        coVerify { queryRefs.findByQueryId(queryId) }

        service.removeQuerySourceRef(queryId)
        coVerify { queryRefs.delete(queryId) }

        service.findQuerySourceRefsByRepository(repositoryId)
        coVerify { queryRefs.findByRepository(repositoryId) }
    }
}
