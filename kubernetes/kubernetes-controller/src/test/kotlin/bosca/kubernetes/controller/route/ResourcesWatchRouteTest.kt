package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionList
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.Watch
import io.fabric8.kubernetes.client.Watcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [ResourcesWatchRoute] — the generic change-notification stream
 * behind `k8sResourcesWatch`. Covers the four resolution paths (well
 * known kind, cluster-scoped kind, dynamic CRD kind, HelmRelease
 * pseudo-kind), the CRD-not-installed skip, and the shared
 * authorization floor.
 */
@OptIn(ExperimentalUuidApi::class)
class ResourcesWatchRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(pathId: String, query: Map<String, String> = emptyMap()): ServerCall {
        val c = mockk<ServerCall>(relaxed = true)
        every { c.pathParameters } returns Parameters.fromSingleValueMap(mapOf("id" to pathId))
        every { c.request.queryParameters } returns Parameters.fromSingleValueMap(query)
        return c
    }

    private fun resource(name: String, namespace: String? = null): GenericKubernetesResource {
        val r = GenericKubernetesResource()
        r.metadata = ObjectMetaBuilder().withName(name).withNamespace(namespace).build()
        return r
    }

    private fun route() = ResourcesWatchRoute(groups, pool, json)

    private fun adminOk(id: bosca.serialization.UUID) {
        justRun { groups.verifyHasAdminGroup(any()) }
        coEvery { pool.get(id) } returns client
    }

    @Test
    fun `streams ADDED MODIFIED and DELETED change events with the kind token`() = runTest {
        val id = UUID.random()
        adminOk(id)
        val dispatcher = client.stubGenericResources()
        val ops = dispatcher.forKind("Service").ops
        val slot = slot<Watcher<GenericKubernetesResource>>()
        every { ops.inAnyNamespace().watch(capture(slot)) } answers {
            val w = slot.captured
            w.eventReceived(Watcher.Action.ADDED, resource("api", "default"))
            w.eventReceived(Watcher.Action.MODIFIED, resource("api", "default"))
            w.eventReceived(Watcher.Action.DELETED, resource("old", "default"))
            w.onClose(null)
            mockk<Watch>(relaxed = true)
        }

        val c = call(id.toString(), mapOf("kinds" to "Service"))
        val cap = c.captureStream()
        route().runExecute(c, ctx)

        assertEquals(3, cap.lines.size)
        assertTrue(cap.lines[0].contains("\"kind\":\"Service\""))
        assertTrue(cap.lines[0].contains("\"action\":\"ADDED\""))
        assertTrue(cap.lines[2].contains("\"action\":\"DELETED\""))
        assertTrue(cap.lines[2].contains("\"name\":\"old\""))
    }

    @Test
    fun `namespace narrows namespaced kinds via inNamespace`() = runTest {
        val id = UUID.random()
        adminOk(id)
        val dispatcher = client.stubGenericResources()
        val ops = dispatcher.forKind("ConfigMap").ops
        val slot = slot<Watcher<GenericKubernetesResource>>()
        every { ops.inNamespace("prod").watch(capture(slot)) } answers {
            val w = slot.captured
            w.eventReceived(Watcher.Action.MODIFIED, resource("app-config", "prod"))
            w.onClose(null)
            mockk<Watch>(relaxed = true)
        }

        val c = call(id.toString(), mapOf("kinds" to "ConfigMap", "namespace" to "prod"))
        val cap = c.captureStream()
        route().runExecute(c, ctx)

        assertEquals(1, cap.lines.size)
        assertTrue(cap.lines[0].contains("app-config"))
    }

    @Test
    fun `cluster-scoped kinds watch without namespace narrowing`() = runTest {
        val id = UUID.random()
        adminOk(id)
        val dispatcher = client.stubGenericResources()
        val ops = dispatcher.forKind("Node").ops
        val slot = slot<Watcher<GenericKubernetesResource>>()
        every { ops.watch(capture(slot)) } answers {
            val w = slot.captured
            w.eventReceived(Watcher.Action.MODIFIED, resource("node-1"))
            w.onClose(null)
            mockk<Watch>(relaxed = true)
        }

        // namespace supplied but irrelevant for a cluster-scoped kind
        val c = call(id.toString(), mapOf("kinds" to "Node", "namespace" to "prod"))
        val cap = c.captureStream()
        route().runExecute(c, ctx)

        assertEquals(1, cap.lines.size)
        assertTrue(cap.lines[0].contains("\"namespace\":null") || !cap.lines[0].contains("\"namespace\":\"prod\""))
    }

    @Test
    fun `unknown kind resolves dynamically against installed CRDs`() = runTest {
        val id = UUID.random()
        adminOk(id)
        val crd = CustomResourceDefinitionBuilder()
            .withNewMetadata().withName("widgets.example.io").endMetadata()
            .withNewSpec()
            .withGroup("example.io")
            .withScope("Namespaced")
            .withNewNames().withKind("Widget").withPlural("widgets").endNames()
            .addNewVersion().withName("v1").withServed(true).withStorage(true).endVersion()
            .endSpec()
            .build()
        val crdList = CustomResourceDefinitionList()
        crdList.items = mutableListOf(crd)
        every { client.apiextensions().v1().customResourceDefinitions().list() } returns crdList

        val dispatcher = client.stubGenericResources()
        val ops = dispatcher.forKind("Widget").ops
        val slot = slot<Watcher<GenericKubernetesResource>>()
        every { ops.inAnyNamespace().watch(capture(slot)) } answers {
            val w = slot.captured
            w.eventReceived(Watcher.Action.ADDED, resource("w1", "default"))
            w.onClose(null)
            mockk<Watch>(relaxed = true)
        }

        val c = call(id.toString(), mapOf("kinds" to "example.io/Widget"))
        val cap = c.captureStream()
        route().runExecute(c, ctx)

        assertEquals(1, cap.lines.size)
        assertTrue(cap.lines[0].contains("\"kind\":\"example.io/Widget\""))
    }

    @Test
    fun `kind whose CRD is not installed is skipped while others stream`() = runTest {
        val id = UUID.random()
        adminOk(id)
        val dispatcher = client.stubGenericResources()
        every {
            dispatcher.forKind("Certificate").ops.inAnyNamespace().watch(any())
        } throws KubernetesClientException("the server could not find the requested resource", 404, null)
        val svcOps = dispatcher.forKind("Service").ops
        val slot = slot<Watcher<GenericKubernetesResource>>()
        every { svcOps.inAnyNamespace().watch(capture(slot)) } answers {
            val w = slot.captured
            w.eventReceived(Watcher.Action.ADDED, resource("api", "default"))
            w.onClose(null)
            mockk<Watch>(relaxed = true)
        }

        val c = call(id.toString(), mapOf("kinds" to "Certificate,Service"))
        val cap = c.captureStream()
        route().runExecute(c, ctx)

        assertEquals(1, cap.lines.size)
        assertTrue(cap.lines[0].contains("\"kind\":\"Service\""))
    }

    @Test
    fun `HelmRelease pseudo-kind watches helm-owned secrets and emits the release name label`() = runTest {
        val id = UUID.random()
        adminOk(id)
        val slot = slot<Watcher<Secret>>()
        every {
            client.secrets().inAnyNamespace().withLabel("owner", "helm").watch(capture(slot))
        } answers {
            val w = slot.captured
            val secret = SecretBuilder()
                .withNewMetadata()
                .withName("sh.helm.release.v1.podinfo.v3")
                .withNamespace("default")
                .addToLabels("owner", "helm")
                .addToLabels("name", "podinfo")
                .endMetadata()
                .build()
            w.eventReceived(Watcher.Action.MODIFIED, secret)
            w.onClose(null)
            mockk<Watch>(relaxed = true)
        }

        val c = call(id.toString(), mapOf("kinds" to "HelmRelease"))
        val cap = c.captureStream()
        route().runExecute(c, ctx)

        assertEquals(1, cap.lines.size)
        assertTrue(cap.lines[0].contains("\"kind\":\"HelmRelease\""))
        assertTrue(cap.lines[0].contains("\"name\":\"podinfo\""))
    }

    @Test
    fun `missing kinds parameter responds BadRequest without opening a stream`() = runTest {
        val id = UUID.random()
        adminOk(id)
        val c = call(id.toString())
        route().runExecute(c, ctx)
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `every opened watch is closed when the stream exits`() = runTest {
        val id = UUID.random()
        adminOk(id)
        val dispatcher = client.stubGenericResources()
        val svcWatch = mockk<Watch>(relaxed = true)
        val nodeWatch = mockk<Watch>(relaxed = true)
        val slot = slot<Watcher<GenericKubernetesResource>>()
        every { dispatcher.forKind("Service").ops.inAnyNamespace().watch(any()) } returns svcWatch
        every { dispatcher.forKind("Node").ops.watch(capture(slot)) } answers {
            // Close from the node watcher so the shared channel ends
            // and the route unwinds through its finally block.
            slot.captured.onClose(null)
            nodeWatch
        }

        val c = call(id.toString(), mapOf("kinds" to "Service,Node"))
        c.captureStream()
        route().runExecute(c, ctx)

        verify { svcWatch.close() }
        verify { nodeWatch.close() }
    }

    @Test
    fun `opened watches are closed when cancellation lands during watch acquisition`() = runTest {
        val id = UUID.random()
        adminOk(id)
        val dispatcher = client.stubGenericResources()
        val serviceWatch = mockk<Watch>(relaxed = true)
        val nodeWatch = mockk<Watch>(relaxed = true)
        lateinit var routeJob: Job
        every { dispatcher.forKind("Service").ops.inAnyNamespace().watch(any()) } returns serviceWatch
        every { dispatcher.forKind("Node").ops.watch(any()) } answers {
            routeJob.cancel(CancellationException("client disconnected"))
            nodeWatch
        }

        val c = call(id.toString(), mapOf("kinds" to "Service,Node"))
        c.captureStream()
        routeJob = launch(start = CoroutineStart.LAZY) {
            route().runExecute(c, ctx)
        }

        routeJob.start()
        routeJob.join()

        verify { serviceWatch.close() }
        verify { nodeWatch.close() }
    }

    @Test
    fun `non-admin is rejected before any watch opens`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        assertFailsWith<SecurityException> {
            route().runExecute(call(UUID.random().toString(), mapOf("kinds" to "Service")), ctx)
        }
    }
}
