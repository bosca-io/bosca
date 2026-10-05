package bosca.artifacts.ml

import bosca.artifacts.ml.routes.mlRequirePermission
import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.security.service.AuthenticationContext
import bosca.server.ServerCall
import bosca.server.ServerResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies [mlRequirePermission] passes through when the evaluator grants access, challenges anonymous
 * callers with a 401 + `WWW-Authenticate`, and 403s an authenticated-but-unauthorized caller.
 */
class MlRequirePermissionTest {

    private val namespace = "model"
    private val repo = "recommender-personalized"

    private fun mockCall(): Pair<ServerCall, ServerResponse> {
        val response = mockk<ServerResponse>(relaxed = true)
        val call = mockk<ServerCall>(relaxed = true)
        every { call.response } returns response
        return call to response
    }

    @Test
    fun `returns true when evaluator grants access`() = runTest {
        val evaluator = mockk<ArtifactPermissionEvaluator>()
        coEvery { evaluator.evaluate(any(), "ml", namespace, repo, "1", ArtifactAction.PUSH, false) } returns true

        val (call, response) = mockCall()
        val auth = mockk<AuthenticationContext>(relaxed = true)
        val result = mlRequirePermission(call, evaluator, auth, namespace, repo, "1", ArtifactAction.PUSH)

        assertTrue(result)
        verify(exactly = 0) { response.header("WWW-Authenticate", any()) }
    }

    @Test
    fun `challenges anonymous caller with 401 and WWW-Authenticate`() = runTest {
        val evaluator = mockk<ArtifactPermissionEvaluator>()
        coEvery { evaluator.evaluate(any(), "ml", namespace, repo, "1", ArtifactAction.PULL, false) } returns false

        val (call, response) = mockCall()
        val auth = mockk<AuthenticationContext>(relaxed = true)
        every { auth.principal() } returns null

        val result = mlRequirePermission(call, evaluator, auth, namespace, repo, "1", ArtifactAction.PULL)

        assertFalse(result)
        verify { response.header("WWW-Authenticate", """Basic realm="Bosca ML Registry"""") }
    }

    @Test
    fun `403s an authenticated but unauthorized caller`() = runTest {
        val evaluator = mockk<ArtifactPermissionEvaluator>()
        coEvery { evaluator.evaluate(any(), "ml", namespace, repo, "1", ArtifactAction.PUSH, false) } returns false

        val (call, response) = mockCall()
        val auth = mockk<AuthenticationContext>(relaxed = true)
        every { auth.principal() } returns mockk(relaxed = true)

        val result = mlRequirePermission(call, evaluator, auth, namespace, repo, "1", ArtifactAction.PUSH)

        assertFalse(result)
        verify(exactly = 0) { response.header("WWW-Authenticate", any()) }
    }
}
