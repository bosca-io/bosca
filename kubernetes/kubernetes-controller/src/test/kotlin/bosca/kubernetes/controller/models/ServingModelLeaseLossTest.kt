@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bosca.kubernetes.controller.models

import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.PodListBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ServingModelLeaseLossTest {
    @Test
    fun `a read completing after lease loss cannot retire an earlier selection`() = runTest {
        val factory = mockk<DistributedLockFactory>()
        val lock = mockk<DistributedLock>()
        val owned = AtomicBoolean(true)
        coEvery { factory.create(any()) } returns lock
        coEvery { lock.tryAcquire(any()) } returns true
        coEvery { lock.renew(any()) } answers { owned.get() }
        coEvery { lock.release() } returns false
        val profile = ServingModelResourcesTest.profile()
        val retired = buildServingModel(profile, ServingModelResourcesTest.export(3)).apply {
            metadata.uid = "old"
            metadata.annotations = mapOf(DRAIN_SINCE to "2026-01-01T00:00:00Z")
        }
        val active = buildServingModel(profile, ServingModelResourcesTest.export(4)).apply { metadata.uid = "active" }
        val client = mockk<KubernetesClient>(relaxed = true)
        every { client.secrets().inNamespace("test").withName("token").get() } returns SecretBuilder()
            .addToData("artifacts_pull_token", Base64.getEncoder().encodeToString("token".toByteArray())).build()
        every { client.genericKubernetesResources(SERVING_MODELS).inNamespace("test")
            .withLabel(SERVING_PROFILE_LABEL, profile.uid).list().items } returns listOf(retired, active)
        val entered = CompletableDeferred<Unit>()
        val gate = CountDownLatch(1)
        every { client.pods().inNamespace("test").list() } answers {
            entered.complete(Unit)
            while (true) {
                try {
                    check(gate.await(10, TimeUnit.SECONDS))
                    break
                } catch (_: InterruptedException) { /* Simulate an uninterruptible in-flight read. */ }
            }
            PodListBuilder().withItems(ServingModelResources(retired).pod()).build()
        }
        val discovery = mockk<ServingModelDiscovery>()
        coEvery { discovery.discover(profile, "token") } returns
            (listOf(ServingModelContext("context", 1, activeModelVersion = 4)) to listOf(ServingModelResourcesTest.export(4)))
        ServingModelController(mockk(), mockk(), discovery, factory, now = { Instant.parse("2026-01-02T00:00:00Z") }).use { controller ->
            supervisorScope {
                val pass = async { controller.reconcile(client, profile) }
                entered.await()
                owned.set(false)
                advanceTimeBy(20_001)
                runCurrent()
                gate.countDown()
                assertFailsWith<IllegalStateException> { pass.await() }
            }
        }
        verify(exactly = 0) { client.resource(any<GenericKubernetesResource>()) }
    }
}
