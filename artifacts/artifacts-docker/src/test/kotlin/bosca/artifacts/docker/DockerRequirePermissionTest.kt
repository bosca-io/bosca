package bosca.artifacts.docker

import bosca.artifacts.docker.routes.DOCKER_DISTRIBUTION_HEADER
import bosca.artifacts.docker.routes.DOCKER_DISTRIBUTION_VERSION
import bosca.artifacts.docker.routes.dockerRequirePermission
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
 * Verifies that [dockerRequirePermission] returns the correct OCI-compliant
 * 401 response with a `WWW-Authenticate` header when access is denied, and
 * passes through silently when access is granted.
 */
class DockerRequirePermissionTest {

    private val namespace = "bosca"
    private val repo = "imageprocessor"

    private fun mockCall(): Pair<ServerCall, ServerResponse> {
        val response = mockk<ServerResponse>(relaxed = true)
        val call = mockk<ServerCall>(relaxed = true)
        every { call.response } returns response
        return call to response
    }

    @Test
    fun `returns true when evaluator grants access`() = runTest {
        val evaluator = mockk<ArtifactPermissionEvaluator>()
        coEvery { evaluator.evaluate(any(), "docker", namespace, repo, null, ArtifactAction.PULL, true) } returns true

        val (call, response) = mockCall()
        val auth = mockk<AuthenticationContext>(relaxed = true)
        val result = dockerRequirePermission(call, evaluator, auth, "docker", namespace, repo, null, ArtifactAction.PULL, true)

        assertTrue(result)
        verify(exactly = 0) { response.header("WWW-Authenticate", any()) }
    }

    @Test
    fun `returns false and sets WWW-Authenticate header when access denied`() = runTest {
        val evaluator = mockk<ArtifactPermissionEvaluator>()
        coEvery { evaluator.evaluate(any(), "docker", namespace, repo, null, ArtifactAction.PULL, false) } returns false

        val (call, response) = mockCall()
        val auth = mockk<AuthenticationContext>(relaxed = true)
        val result = dockerRequirePermission(call, evaluator, auth, "docker", namespace, repo, null, ArtifactAction.PULL, false)

        assertFalse(result)
        verify { response.header("WWW-Authenticate", """Basic realm="Bosca Registry"""") }
        verify { response.header(DOCKER_DISTRIBUTION_HEADER, DOCKER_DISTRIBUTION_VERSION) }
    }

    @Test
    fun `sends 401 challenge for push action without credentials`() = runTest {
        val evaluator = mockk<ArtifactPermissionEvaluator>()
        coEvery { evaluator.evaluate(any(), "docker", namespace, repo, null, ArtifactAction.PUSH, false) } returns false

        val (call, response) = mockCall()
        val auth = mockk<AuthenticationContext>(relaxed = true)
        val result = dockerRequirePermission(call, evaluator, auth, "docker", namespace, repo, null, ArtifactAction.PUSH, false)

        assertFalse(result)
        verify { response.header("WWW-Authenticate", """Basic realm="Bosca Registry"""") }
    }

    @Test
    fun `allows access on public namespace anonymous pull`() = runTest {
        val evaluator = ArtifactPermissionEvaluator(mockk())
        val (call, response) = mockCall()
        val auth = mockk<AuthenticationContext>(relaxed = true)
        every { auth.principal() } returns null

        val result = dockerRequirePermission(call, evaluator, auth, "docker", "public-ns", repo, null, ArtifactAction.PULL, true)

        assertTrue(result)
        verify(exactly = 0) { response.header("WWW-Authenticate", any()) }
    }

    @Test
    fun `denies access on private namespace anonymous pull`() = runTest {
        val evaluator = ArtifactPermissionEvaluator(mockk())
        val (call, response) = mockCall()
        val auth = mockk<AuthenticationContext>(relaxed = true)
        every { auth.principal() } returns null

        val result = dockerRequirePermission(call, evaluator, auth, "docker", "private-ns", repo, null, ArtifactAction.PULL, false)

        assertFalse(result)
        verify { response.header("WWW-Authenticate", """Basic realm="Bosca Registry"""") }
    }
}
