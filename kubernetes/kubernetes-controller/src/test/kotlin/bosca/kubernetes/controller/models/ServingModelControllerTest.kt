package bosca.kubernetes.controller.models

import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodConditionBuilder
import io.fabric8.kubernetes.api.model.PodListBuilder
import io.fabric8.kubernetes.api.model.PodStatusBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.DeletableWithOptions
import io.fabric8.kubernetes.client.dsl.NamespaceableResource
import io.fabric8.kubernetes.client.dsl.ReplaceDeletable
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServingModelControllerTest {
    private val profile = ServingModelResourcesTest.profile()
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val client = mockk<KubernetesClient>(relaxed = true)
    private val discovery = mockk<ServingModelDiscovery>()
    private val deleted = mutableListOf<String>()

    private fun model(version: Long, annotations: Map<String, String> = emptyMap()) =
        buildServingModel(profile, ServingModelResourcesTest.export(version)).apply {
            metadata.uid = "model-$version"
            metadata.annotations = annotations
        }

    private fun readyPod(model: GenericKubernetesResource): Pod = ServingModelResources(model).pod().apply {
        status = PodStatusBuilder().withPhase("Running")
            .withConditions(PodConditionBuilder().withType("Ready").withStatus("True").build()).build()
    }

    private fun stubCluster(models: List<GenericKubernetesResource>, pods: List<Pod>, onResource: (HasMetadata) -> Unit = {}) {
        val encoded = { value: String -> Base64.getEncoder().encodeToString(value.toByteArray()) }
        every { client.secrets().inNamespace("test").withName("token").get() } returns SecretBuilder()
            .addToData("artifacts_pull_token", encoded("token")).addToData("bosca_token", encoded("control")).build()
        every { client.genericKubernetesResources(SERVING_MODELS).inNamespace("test")
            .withLabel(SERVING_PROFILE_LABEL, profile.uid).list().items } returns models
        every { client.pods().inNamespace("test").withLabel(SERVING_MODEL_LABEL).list() } returns
            PodListBuilder().withItems(pods).build()
        every { client.resource(any<HasMetadata>()) } answers {
            val item = firstArg<HasMetadata>()
            onResource(item)
            mockk<NamespaceableResource<HasMetadata>>(relaxed = true) {
                every { get() } returns item
                every { update() } returns item
                every { patchStatus() } returns item
                every { lockResourceVersion() } answers {
                    mockk<ReplaceDeletable<HasMetadata>>(relaxed = true, moreInterfaces = arrayOf(DeletableWithOptions::class)) {
                        every { (this@mockk as DeletableWithOptions).withPropagationPolicy(any()) } answers {
                            deleted += item.metadata.name
                            mockk(relaxed = true)
                        }
                    }
                }
            }
        }
    }

    private fun locks(owned: AtomicBoolean = AtomicBoolean(true)): DistributedLockFactory {
        val factory = mockk<DistributedLockFactory>()
        val lock = mockk<DistributedLock>()
        coEvery { factory.create(any()) } returns lock
        coEvery { lock.tryAcquire(any()) } returns true
        coEvery { lock.renew(any()) } answers { owned.get() }
        coEvery { lock.release() } returns true
        return factory
    }

    @Test
    fun `a ready candidate that is never activated is failed at the startup deadline`() = runBlocking {
        val active = model(4)
        val candidate = model(5, mapOf(
            SELECTION_REVISION to "1", PREPARED_REVISION to "1", STARTUP_SINCE to start.toString(),
        ))
        stubCluster(listOf(active, candidate), listOf(readyPod(active), readyPod(candidate)))
        coEvery { discovery.discover(profile, "token") } returns (listOf(ServingModelContext("context", 1, activeModelVersion = 4)) to
            listOf(ServingModelResourcesTest.export(4).copy(status = "COMPLETED"), ServingModelResourcesTest.export(5)))
        coEvery { discovery.failModel(profile, "control", 5, 1, any()) } returns true

        ServingModelController(mockk(), mockk(), discovery, locks(), now = { start.plusSeconds(1_799) }).use {
            it.reconcile(client, profile)
        }
        coVerify(exactly = 0) { discovery.failModel(any(), any(), any(), any(), any()) }
        assertEquals(emptyList(), deleted)

        ServingModelController(mockk(), mockk(), discovery, locks(), now = { start.plusSeconds(1_800) }).use {
            it.reconcile(client, profile)
        }
        coVerify(exactly = 1) {
            discovery.failModel(profile, "control", 5, 1, "Serving candidate was not activated within startupTimeoutSeconds (1800)")
        }
        assertEquals(listOf(candidate.metadata.name), deleted)
    }

    @Test
    fun `one model's failure does not stop reconciliation of the profile's other models`() = runBlocking {
        val candidate = model(3, mapOf(SELECTION_REVISION to "1"))
        val active = model(4)
        val foreignPod = readyPod(candidate).apply {
            metadata.ownerReferences = listOf(OwnerReferenceBuilder().withUid("someone-else").withController(true).build())
        }
        stubCluster(listOf(candidate, active), listOf(foreignPod))
        coEvery { discovery.discover(profile, "token") } returns (listOf(ServingModelContext("context", 1, activeModelVersion = 4)) to
            listOf(ServingModelResourcesTest.export(3), ServingModelResourcesTest.export(4).copy(status = "COMPLETED")))

        ServingModelController(mockk(), mockk(), discovery, locks(), now = { start }).use { it.reconcile(client, profile) }

        verify { client.resource(match<HasMetadata> { it is Pod && it.metadata.name == active.metadata.name }) }
    }

    @Test
    fun `a lease lost while reconciling one model ends the cycle instead of continuing with the next`() = runBlocking {
        val owned = AtomicBoolean(true)
        val candidate = model(3, mapOf(SELECTION_REVISION to "1"))
        val active = model(4)
        val foreignPod = readyPod(candidate).apply {
            metadata.ownerReferences = listOf(OwnerReferenceBuilder().withUid("someone-else").withController(true).build())
        }
        // The candidate's first owned-resource write observes that ownership moved elsewhere.
        stubCluster(listOf(candidate, active), listOf(foreignPod)) { item ->
            if (item.metadata.name == candidate.metadata.name && item !is GenericKubernetesResource) owned.set(false)
        }
        coEvery { discovery.discover(profile, "token") } returns (listOf(ServingModelContext("context", 1, activeModelVersion = 4)) to
            listOf(ServingModelResourcesTest.export(3), ServingModelResourcesTest.export(4).copy(status = "COMPLETED")))

        ServingModelController(mockk(), mockk(), discovery, locks(owned), now = { start }).use { controller ->
            assertFailsWith<IllegalStateException> { controller.reconcile(client, profile) }
        }
        verify(exactly = 0) { client.resource(match<HasMetadata> { it is Pod && it.metadata.name == active.metadata.name }) }
    }
}
