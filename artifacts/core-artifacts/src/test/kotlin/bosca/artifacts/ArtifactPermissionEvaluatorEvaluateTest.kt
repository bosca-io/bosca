package bosca.artifacts

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.model.ArtifactNamespace
import bosca.artifacts.service.ArtifactNamespacePermissionEvaluator
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [ArtifactPermissionEvaluator.evaluate], covering the top-level
 * authorization logic: anonymous access rules, JWT/session delegation to the
 * namespace permission model, scope presence checks, and delegation to scope matching.
 *
 * Companion-level [ArtifactPermissionEvaluator.matchesScope] and
 * [ArtifactPermissionEvaluator.matchesGlob] are tested separately in
 * [ArtifactPermissionEvaluatorTest].
 */
class ArtifactPermissionEvaluatorEvaluateTest {

    private val repoService = mockk<ArtifactRepositoryService>()
    private val namespaceEvaluator = mockk<ArtifactNamespacePermissionEvaluator>()
    private val evaluator = ArtifactPermissionEvaluator(namespaceEvaluator)

    // Convenience defaults for artifact coordinates used across tests
    private val type = "docker"
    private val namespace = "acme"
    private val repository = "api"
    private val version = "v1.0.0"

    // -- Anonymous (null auth) access --

    @Test
    fun `null auth with public namespace and PULL is allowed`() = runTest {
        assertTrue(
            evaluator.evaluate(
                auth = null,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.PULL,
                isPublicNamespace = true,
            )
        )
    }

    @Test
    fun `null auth with private namespace and PULL is denied`() = runTest {
        assertFalse(
            evaluator.evaluate(
                auth = null,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.PULL,
                isPublicNamespace = false,
            )
        )
    }

    @Test
    fun `null auth with public namespace and PUSH is denied`() = runTest {
        assertFalse(
            evaluator.evaluate(
                auth = null,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.PUSH,
                isPublicNamespace = true,
            )
        )
    }

    @Test
    fun `null auth with public namespace and ADMIN is denied`() = runTest {
        assertFalse(
            evaluator.evaluate(
                auth = null,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.ADMIN,
                isPublicNamespace = true,
            )
        )
    }

    // -- Non-scoped principal (JWT/session user): authorized via the namespace permission model --

    @Test
    fun `jwt principal is allowed when the namespace permission grants the action`() = runTest {
        val auth = authWith(AuthenticatedPrincipal(Principal(), emptyList()))
        val ns = ArtifactNamespace(id = UUID.random(), name = namespace)
        every { namespaceEvaluator.service } returns repoService
        coEvery { repoService.getNamespaceByName(namespace) } returns ns
        coEvery { namespaceEvaluator.isAllowed(auth, ns, PermissionAction.VIEW) } returns true

        assertTrue(
            evaluator.evaluate(auth, type, namespace, repository, version, ArtifactAction.PULL),
        )
    }

    @Test
    fun `jwt principal is denied when the namespace permission denies the action`() = runTest {
        val auth = authWith(AuthenticatedPrincipal(Principal(), emptyList()))
        val ns = ArtifactNamespace(id = UUID.random(), name = namespace)
        every { namespaceEvaluator.service } returns repoService
        coEvery { repoService.getNamespaceByName(namespace) } returns ns
        coEvery { namespaceEvaluator.isAllowed(auth, ns, PermissionAction.EDIT) } returns false

        assertFalse(
            evaluator.evaluate(auth, type, namespace, repository, version, ArtifactAction.PUSH),
        )
    }

    @Test
    fun `jwt principal is denied when the namespace does not exist`() = runTest {
        val auth = authWith(AuthenticatedPrincipal(Principal(), emptyList()))
        every { namespaceEvaluator.service } returns repoService
        coEvery { repoService.getNamespaceByName(namespace) } returns null

        assertFalse(
            evaluator.evaluate(auth, type, namespace, repository, version, ArtifactAction.PULL),
        )
    }

    @Test
    fun `jwt principal can pull a public namespace without a grant`() = runTest {
        val auth = authWith(AuthenticatedPrincipal(Principal(), emptyList()))
        // The public-pull shortcut allows it — the namespace evaluator is never consulted.
        assertTrue(
            evaluator.evaluate(
                auth = auth,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.PULL,
                isPublicNamespace = true,
            ),
        )
    }

    // -- ScopedAuthenticatedPrincipal with null scopes --

    @Test
    fun `scoped principal with null scopes is denied`() = runTest {
        val principal = ScopedAuthenticatedPrincipal(
            principal = Principal(),
            allGroups = emptyList(),
            scopes = null,
            allowedGroupIds = null,
            credentialId = 1L,
        )
        val auth = mockk<AuthenticationContext> {
            every { principal() } returns principal
        }
        assertFalse(
            evaluator.evaluate(
                auth = auth,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.PULL,
            )
        )
    }

    // -- ScopedAuthenticatedPrincipal with scopes but no artifacts: scopes --

    @Test
    fun `scoped principal with non-artifact scopes is denied`() = runTest {
        val principal = ScopedAuthenticatedPrincipal(
            principal = Principal(),
            allGroups = emptyList(),
            scopes = listOf("content:read", "admin:users"),
            allowedGroupIds = null,
            credentialId = 2L,
        )
        val auth = mockk<AuthenticationContext> {
            every { principal() } returns principal
        }
        assertFalse(
            evaluator.evaluate(
                auth = auth,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.PULL,
            )
        )
    }

    // -- ScopedAuthenticatedPrincipal with matching artifacts: scope --

    @Test
    fun `scoped principal with artifacts pull scope can PULL`() = runTest {
        val principal = scopedPrincipal(listOf("artifacts:*:*:*:pull"))
        val auth = authWith(principal)
        assertTrue(
            evaluator.evaluate(
                auth = auth,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.PULL,
            )
        )
    }

    @Test
    fun `scoped principal with artifacts pull scope cannot PUSH`() = runTest {
        val principal = scopedPrincipal(listOf("artifacts:*:*:*:pull"))
        val auth = authWith(principal)
        assertFalse(
            evaluator.evaluate(
                auth = auth,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.PUSH,
            )
        )
    }

    // -- Public namespace + PULL + scoped token --

    @Test
    fun `public namespace pull denied for scoped token without matching scope`() = runTest {
        // Token has artifact scopes but for a different namespace — scoped tokens
        // must always go through scope matching, even on public namespaces.
        // Anonymous access handles public pulls; scoped tokens enforce least-privilege.
        val principal = scopedPrincipal(listOf("artifacts:docker:other-ns/other-repo:*:pull"))
        val auth = authWith(principal)
        assertFalse(
            evaluator.evaluate(
                auth = auth,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.PULL,
                isPublicNamespace = true,
            )
        )
    }

    @Test
    fun `public namespace pull allowed for scoped token with matching scope`() = runTest {
        // Token has a scope that covers the requested namespace — pull is allowed
        val principal = scopedPrincipal(listOf("artifacts:docker:acme/*:*:pull"))
        val auth = authWith(principal)
        assertTrue(
            evaluator.evaluate(
                auth = auth,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.PULL,
                isPublicNamespace = true,
            )
        )
    }

    @Test
    fun `public namespace push still requires matching scope`() = runTest {
        val principal = scopedPrincipal(listOf("artifacts:docker:other-ns/other-repo:*:push"))
        val auth = authWith(principal)
        assertFalse(
            evaluator.evaluate(
                auth = auth,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.PUSH,
                isPublicNamespace = true,
            )
        )
    }

    // -- Fine-grained scope matching through evaluate --

    @Test
    fun `fine-grained scope matches correct namespace and repo`() = runTest {
        val principal = scopedPrincipal(listOf("artifacts:docker:acme/api:v1.*:pull"))
        val auth = authWith(principal)
        assertTrue(
            evaluator.evaluate(
                auth = auth,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = "v1.2.3",
                action = ArtifactAction.PULL,
            )
        )
    }

    @Test
    fun `fine-grained scope rejects wrong version`() = runTest {
        val principal = scopedPrincipal(listOf("artifacts:docker:acme/api:v1.*:pull"))
        val auth = authWith(principal)
        assertFalse(
            evaluator.evaluate(
                auth = auth,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = "v2.0.0",
                action = ArtifactAction.PULL,
            )
        )
    }

    @Test
    fun `fine-grained scope rejects wrong type`() = runTest {
        val principal = scopedPrincipal(listOf("artifacts:maven:acme/api:*:pull"))
        val auth = authWith(principal)
        assertFalse(
            evaluator.evaluate(
                auth = auth,
                type = "docker",
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.PULL,
            )
        )
    }

    @Test
    fun `admin scope grants pull through evaluate`() = runTest {
        val principal = scopedPrincipal(listOf("artifacts:docker:acme/api:*:admin"))
        val auth = authWith(principal)
        assertTrue(
            evaluator.evaluate(
                auth = auth,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.PULL,
            )
        )
    }

    @Test
    fun `multiple scopes - first non-matching, second matching`() = runTest {
        val principal = scopedPrincipal(
            listOf(
                "artifacts:npm:@vendor/sdk:*:pull",
                "artifacts:docker:acme/api:*:push",
            )
        )
        val auth = authWith(principal)
        assertTrue(
            evaluator.evaluate(
                auth = auth,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.PUSH,
            )
        )
    }

    @Test
    fun `auth returning null principal with private namespace is denied`() = runTest {
        val auth = mockk<AuthenticationContext> {
            every { principal() } returns null
        }
        assertFalse(
            evaluator.evaluate(
                auth = auth,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.PULL,
                isPublicNamespace = false,
            )
        )
    }

    @Test
    fun `auth returning null principal with public namespace allows PULL`() = runTest {
        val auth = mockk<AuthenticationContext> {
            every { principal() } returns null
        }
        assertTrue(
            evaluator.evaluate(
                auth = auth,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.PULL,
                isPublicNamespace = true,
            )
        )
    }

    // -- verify --

    @Test
    fun `verify throws SecurityException when permission is denied`() = runTest {
        val exception = assertFailsWith<SecurityException> {
            evaluator.verify(
                auth = null,
                type = type,
                namespace = namespace,
                repository = repository,
                versionOrTag = version,
                action = ArtifactAction.PUSH,
                isPublicNamespace = false,
            )
        }
        assertTrue(exception.message!!.contains("Insufficient permissions"))
    }

    @Test
    fun `verify does not throw when permission is granted`() = runTest {
        evaluator.verify(
            auth = null,
            type = type,
            namespace = namespace,
            repository = repository,
            versionOrTag = version,
            action = ArtifactAction.PULL,
            isPublicNamespace = true,
        )
    }

    // -- Helpers --

    private fun scopedPrincipal(scopes: List<String>): ScopedAuthenticatedPrincipal =
        ScopedAuthenticatedPrincipal(
            principal = Principal(),
            allGroups = emptyList(),
            scopes = scopes,
            allowedGroupIds = null,
            credentialId = 1L,
        )

    private fun authWith(principal: AuthenticatedPrincipal): AuthenticationContext =
        mockk {
            every { principal() } returns principal
        }
}
