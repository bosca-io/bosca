package bosca.kubernetes.controller.route

import io.fabric8.kubernetes.client.KubernetesClientException
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Predicate that distinguishes "Gateway API CRDs are absent from the cluster"
 * from every other fabric8 error. The Gateway routes treat the former as
 * "no resources" (empty list response) and rethrow everything else.
 *
 * A bare cluster (kind, k3s, a fresh EKS without the Gateway API operator)
 * surfaces missing CRDs as a 404 from the API server — fabric8 then wraps
 * the response code on `KubernetesClientException`. The studio's Gateway
 * tab should render an empty state in that case rather than a 500 banner.
 */
class GatewayApiRoutesTest {

    @Test
    fun `isCrdNotInstalled returns true for fabric8 404`() {
        val e = mockk<KubernetesClientException>().also { every { it.code } returns 404 }
        assertTrue(isCrdNotInstalled(e))
    }

    @Test
    fun `isCrdNotInstalled returns false for other fabric8 status codes`() {
        for (code in intArrayOf(401, 403, 409, 500, 503)) {
            val e = mockk<KubernetesClientException>().also { every { it.code } returns code }
            assertFalse(isCrdNotInstalled(e), "code=$code")
        }
    }

    @Test
    fun `isCrdNotInstalled returns false for unrelated exception types`() {
        assertFalse(isCrdNotInstalled(RuntimeException("network")))
        assertFalse(isCrdNotInstalled(IllegalStateException("connection")))
        assertFalse(isCrdNotInstalled(NullPointerException()))
    }
}
