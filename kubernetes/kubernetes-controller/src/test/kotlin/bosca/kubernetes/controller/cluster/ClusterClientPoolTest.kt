package bosca.kubernetes.controller.cluster

import bosca.kubernetes.service.ClusterCredentialService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [ClusterClientPool]'s lazy-build, single-flight, and
 * invalidation behaviour. The pool is the trust boundary between
 * stored credentials and the fabric8 client cache, so:
 *
 *   * Missing kubeconfig must throw a meaningful error (resolver layer
 *     maps it to "Cluster not found").
 *   * Repeated lookups for the same cluster must return the same
 *     cached instance — fabric8 clients own an OkHttp connection pool
 *     and rebuilding them per request is a leak.
 *   * Invalidation must drop the cache, close the fabric8 client, and
 *     notify listeners — the informer registry needs that signal to
 *     tear down watches.
 *
 * We use a real kubeconfig string (minimal) so `Config.fromKubeconfig`
 * succeeds without mocking fabric8 internals.
 */
@OptIn(ExperimentalUuidApi::class)
class ClusterClientPoolTest {

    private val credentials = mockk<ClusterCredentialService>()

    private val minimalKubeconfig = """
        apiVersion: v1
        kind: Config
        clusters:
        - name: test
          cluster:
            server: https://api.example.invalid
            insecure-skip-tls-verify: true
        contexts:
        - name: test
          context:
            cluster: test
            user: test
        current-context: test
        users:
        - name: test
          user:
            token: t
    """.trimIndent()

    @AfterTest
    fun teardown() {
        io.mockk.unmockkAll()
    }

    @Test
    fun `get throws when no kubeconfig is stored for the cluster`() = runTest {
        val pool = ClusterClientPool(credentials)
        val id = UUID.random()
        coEvery { credentials.load(id) } returns null

        val ex = assertFailsWith<IllegalStateException> { pool.get(id) }
        assertTrue(ex.message!!.contains("No kubeconfig stored"))
        assertTrue(ex.message!!.contains(id.toString()))
    }

    @Test
    fun `get caches the fabric8 client on second call for the same id`() = runTest {
        val pool = ClusterClientPool(credentials)
        val id = UUID.random()
        coEvery { credentials.load(id) } returns minimalKubeconfig

        val first = pool.get(id)
        val second = pool.get(id)
        assertSame(first, second, "second lookup must return the cached client")
        // Credentials should only be loaded once even across two `get` calls.
        coVerify(exactly = 1) { credentials.load(id) }
        pool.close()
    }

    @Test
    fun `get builds independent clients for different cluster ids`() = runTest {
        val pool = ClusterClientPool(credentials)
        val a = UUID.random()
        val b = UUID.random()
        coEvery { credentials.load(a) } returns minimalKubeconfig
        coEvery { credentials.load(b) } returns minimalKubeconfig

        val ca = pool.get(a)
        val cb = pool.get(b)
        assertTrue(ca !== cb, "different cluster ids must yield different clients")
        pool.close()
    }

    @Test
    fun `invalidate drops the cache and forces a rebuild on next get`() = runTest {
        val pool = ClusterClientPool(credentials)
        val id = UUID.random()
        coEvery { credentials.load(id) } returns minimalKubeconfig

        val first = pool.get(id)
        pool.invalidate(id)
        val second = pool.get(id)

        assertTrue(first !== second, "post-invalidate get must return a fresh client")
        coVerify(exactly = 2) { credentials.load(id) }
        pool.close()
    }

    @Test
    fun `invalidate is a no-op for an unknown id`() = runTest {
        val pool = ClusterClientPool(credentials)
        // Should not throw; the cache and listeners are simply not touched.
        pool.invalidate(UUID.random())
        pool.close()
    }

    @Test
    fun `invalidate fires registered listeners`() = runTest {
        val pool = ClusterClientPool(credentials)
        val id = UUID.random()
        coEvery { credentials.load(id) } returns minimalKubeconfig

        val notified = mutableListOf<UUID>()
        pool.onInvalidate { notified += it }
        pool.get(id)

        pool.invalidate(id)
        assertEquals(listOf(id), notified)
        pool.close()
    }

    @Test
    fun `invalidate listener failures don't break the invalidation flow`() = runTest {
        val pool = ClusterClientPool(credentials)
        val id = UUID.random()
        coEvery { credentials.load(id) } returns minimalKubeconfig

        var thirdFired = false
        pool.onInvalidate { error("boom") }     // listener 1: throws
        pool.onInvalidate { /* listener 2: silent */ }
        pool.onInvalidate { thirdFired = true } // listener 3: must still fire

        pool.get(id)
        pool.invalidate(id)
        assertTrue(thirdFired, "subsequent listeners must run even if an earlier one threw")
        pool.close()
    }

    @Test
    fun `close clears the cache so a subsequent get rebuilds`() = runTest {
        val pool = ClusterClientPool(credentials)
        val id = UUID.random()
        coEvery { credentials.load(id) } returns minimalKubeconfig

        pool.get(id)
        pool.close()

        // After close, the cache is empty; the next get rebuilds via credentials.load.
        pool.get(id)
        coVerify(exactly = 2) { credentials.load(id) }
        pool.close()
    }
}
