package bosca.kubernetes.controller.models

import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.DeletableWithOptions
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import bosca.kubernetes.service.withServingModelLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.Dispatcher
import mockwebserver3.RecordedRequest
import mockwebserver3.QueueDispatcher
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the controller against an isolated real Kubernetes API when explicitly enabled. */
class ServingModelControllerIntegrationTest {
    @Test
    fun `candidate readiness restart recovery selection draining and discovery failure preserve the active pod`() = runBlocking {
        val kubeconfig = System.getenv("SERVING_MODEL_TEST_KUBECONFIG")
        assumeTrue("Set SERVING_MODEL_TEST_KUBECONFIG to an isolated test cluster", !kubeconfig.isNullOrBlank())
        val namespace = "serving-test-${UUID.randomUUID().toString().take(8)}"
        val http = OkHttpClient()
        MockWebServer().use { server ->
            server.start()
            val failedVersions = ConcurrentHashMap.newKeySet<Long>()
            val reported = AtomicInteger()
            val prepared = ConcurrentHashMap<Long, Long>()
            var verifyClaim: (Long, Long) -> Unit = { _, _ -> }
            val queued = server.dispatcher as QueueDispatcher
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = request.body?.utf8().orEmpty()
                    if ("mutation ServingPrepared" in body) {
                        assertEquals("Bearer control-token", request.headers["Authorization"])
                        val variables = Json.parseToJsonElement(body).jsonObject.getValue("variables").jsonObject
                        val version = variables.getValue("version").jsonPrimitive.long
                        val revision = variables.getValue("selectionRevision").jsonPrimitive.long
                        verifyClaim(version, revision)
                        prepared[version] = revision
                        assertEquals("http://serving-$version.$namespace.svc:8501", variables.getValue("endpoint").jsonPrimitive.content)
                        return MockResponse(body = """{"data":{"recommendation":{"contexts":{"modelServingPrepared":true}}}}""")
                    }
                    if ("mutation ServingFailure" !in body) return queued.dispatch(request)
                    assertEquals("Bearer control-token", request.headers["Authorization"])
                    val variables = Json.parseToJsonElement(body).jsonObject.getValue("variables").jsonObject
                    failedVersions.add(variables.getValue("version").jsonPrimitive.long)
                    assertEquals(if (variables.getValue("version").jsonPrimitive.long == 7L) 4L else 1L,
                        variables.getValue("selectionRevision").jsonPrimitive.long)
                    reported.incrementAndGet()
                    return MockResponse(body = """{"data":{"recommendation":{"contexts":{"modelServingFailed":true}}}}""")
                }
            }
            KubernetesClientBuilder().withConfig(Config.fromKubeconfig(Files.readString(Path.of(requireNotNull(kubeconfig))))).build().use { client ->
                verifyClaim = { version, revision ->
                    val claimed = client.genericKubernetesResources(SERVING_MODELS).inNamespace(namespace).withName("serving-$version").get()
                    assertEquals(revision.toString(), claimed.metadata.annotations[SELECTION_REVISION])
                    assertNull(claimed.metadata.deletionTimestamp)
                }
                for (name in listOf("servingmodelprofiles", "servingmodels")) {
                    Files.newInputStream(Path.of("../../helm/bosca-kubernetes-controller/crds/$name.kubernetes.bosca.io.yaml")).use { stream ->
                        client.load(stream).items().forEach { client.resource(it).fieldManager("serving-model-test").serverSideApply() }
                    }
                }
                withTimeout(30_000) {
                    while (listOf("servingmodelprofiles", "servingmodels").any { name ->
                        client.apiextensions().v1().customResourceDefinitions().withName("$name.kubernetes.bosca.io").get()
                            .status?.conditions.orEmpty().none { it.type == "Established" && it.status == "True" }
                    }) delay(250)
                }
                client.namespaces().resource(NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build()).create()
                try {
                    client.secrets().inNamespace(namespace).resource(SecretBuilder().withNewMetadata().withName("token")
                        .endMetadata().addToStringData("artifacts_pull_token", "test-token").addToStringData("bosca_token", "control-token").build()).create()
                    val template = Serialization.unmarshal("""
                        {"spec":{"automountServiceAccountToken":false,"terminationGracePeriodSeconds":1,"containers":[{"name":"server","image":"node:22-alpine",
                          "command":["node","-e","require('http').createServer((q,r)=>{r.end(require('fs').readFileSync('/config/models.config'))}).listen(8501,'0.0.0.0')"],
                          "ports":[{"name":"http","containerPort":8501}],
                          "readinessProbe":{"tcpSocket":{"port":"http"},"initialDelaySeconds":5,"periodSeconds":1},
                          "volumeMounts":[{"name":"model-config","mountPath":"/config","readOnly":true}]}],
                          "initContainers":[{"name":"download-models","image":"node:22-alpine","command":["/bin/sh","-c","true"]}],
                          "volumes":[{"name":"models","emptyDir":{}}]}}
                    """.trimIndent(), Map::class.java)
                    val resource = ServingModelResourcesTest.profile(mapOf("boscaUrl" to server.url("/").toString(),
                        "podTemplate" to template, "drainSeconds" to 10, "failureGraceSeconds" to 0, "startupTimeoutSeconds" to 30)).resource.apply {
                        metadata.namespace = namespace
                        metadata.uid = null
                    }
                    val profile = ServingModelProfile(client.resource(resource).create())
                    var clock = Instant.parse("2026-01-01T00:00:00Z")
                    val locks = testServingLocks()
                    fun controller() = ServingModelController(mockk(), mockk(), ServingModelDiscovery(http, Json), locks, now = { clock })
                    var controller = controller()
                    var active = 3L
                    var pending: Long? = null
                    var selectionRevision = 1L
                    var requested = false
                    fun body(): String {
                        val exports = listOfNotNull(active, pending).distinct().filter { it !in failedVersions }.joinToString(",") { version ->
                            """{"contextId":"context","version":$version,"selectionRevision":1,"status":"${if (version == active || requested) "COMPLETED" else "RUNNING"}","contentModelName":"content","personalizedModelName":"personalized","personalized":true}"""
                        }
                        return """{"data":{"recommendation":{"contexts":{"all":[{"id":"context","selectionRevision":$selectionRevision,"activeModelVersion":$active,"requestedModelVersion":${if (requested) pending else null}}],"servingModels":[$exports]}}}}"""
                    }
                    suspend fun reconcile() {
                        queued.enqueue(MockResponse(body = body()))
                        controller.reconcile(client, profile)
                    }
                    suspend fun awaitPhase(version: Long, phase: String) {
                        withTimeout(120_000) {
                            while (true) {
                                reconcile()
                                val model = client.genericKubernetesResources(SERVING_MODELS).inNamespace(namespace).withName("serving-$version").get()
                                if ((model?.additionalProperties?.get("status") as? Map<*, *>)?.get("phase") == phase) break
                                delay(500)
                            }
                        }
                    }
                    suspend fun assertActiveServes(version: Long) {
                        client.pods().inNamespace(namespace).withName("serving-$version").portForward(8501).use { forward ->
                            http.newCall(Request.Builder().url("http://127.0.0.1:${forward.localPort}/").build()).execute().use {
                                assertTrue(it.isSuccessful)
                                assertTrue(it.body.string().contains("versions: $version"))
                            }
                        }
                    }
                    awaitPhase(3, "Active")
                    val firstUid = requireNotNull(client.pods().inNamespace(namespace).withName("serving-3").get()).metadata.uid
                    pending = 4
                    withServingModelLock(locks) {
                        val count = server.requestCount
                        controller().use { overlapping -> overlapping.reconcile(client, profile) }
                        assertEquals(count, server.requestCount)
                        assertNull(client.pods().inNamespace(namespace).withName("serving-4").get())
                    }
                    reconcile()
                    assertActiveServes(3)
                    assertEquals(firstUid, client.pods().inNamespace(namespace).withName("serving-3").get().metadata.uid)
                    controller.close()
                    controller = controller()
                    awaitPhase(4, "Ready")
                    assertActiveServes(3)
                    assertActiveServes(4)
                    assertEquals(2, client.pods().inNamespace(namespace).list().items.size)
                    val retained = client.pods().inNamespace(namespace).list().items.associate { it.metadata.name to it.metadata.uid }
                    queued.enqueue(MockResponse(code = 503))
                    assertFailsWith<IllegalStateException> { controller.reconcile(client, profile) }
                    assertEquals(retained, client.pods().inNamespace(namespace).list().items.associate { it.metadata.name to it.metadata.uid })
                    active = 4
                    pending = 5
                    reconcile()
                    assertNotNull(client.pods().inNamespace(namespace).withName("serving-3").get())
                    assertNull(client.pods().inNamespace(namespace).withName("serving-5").get())
                    assertActiveServes(4)
                    // A new activation intent must fence a delete captured by an earlier holder.
                    val staleRetirement = client.genericKubernetesResources(SERVING_MODELS).inNamespace(namespace).withName("serving-3").get()
                    val staleResourceVersion = staleRetirement.metadata.resourceVersion
                    clock = clock.plusSeconds(11)
                    pending = 3
                    requested = true
                    selectionRevision = 2
                    reconcile()
                    assertEquals(2L, prepared[3])
                    val claimed = client.genericKubernetesResources(SERVING_MODELS).inNamespace(namespace).withName("serving-3").get()
                    assertNotEquals(staleResourceVersion, claimed.metadata.resourceVersion)
                    val staleDelete = client.resource(staleRetirement).lockResourceVersion() as DeletableWithOptions
                    val conflict = assertFailsWith<KubernetesClientException> {
                        staleDelete.withPropagationPolicy(DeletionPropagation.FOREGROUND).delete()
                    }
                    assertEquals(409, conflict.code)
                    assertActiveServes(3)
                    active = 3
                    pending = 4
                    requested = false
                    reconcile()
                    assertEquals(firstUid, client.pods().inNamespace(namespace).withName("serving-3").get().metadata.uid)
                    active = 4
                    pending = 5
                    reconcile()
                    val failing = client.genericKubernetesResources(SERVING_MODELS).inNamespace(namespace).withName("serving-5").get()
                    @Suppress("UNCHECKED_CAST")
                    val failingSpec = failing.additionalProperties["spec"] as MutableMap<String, Any?>
                    val brokenTemplate = Serialization.asJson(template).replace("\"true\"", "\"exit 7\"")
                    failingSpec["podTemplate"] = Serialization.unmarshal(brokenTemplate, Map::class.java)
                    client.resource(failing).update()
                    clock = clock.plusSeconds(11)
                    withTimeout(60_000) {
                        while (client.pods().inNamespace(namespace).withName("serving-3").get() != null) {
                            reconcile()
                            delay(250)
                        }
                    }
                    withTimeout(120_000) {
                        while (reported.get() == 0) { reconcile(); delay(250) }
                    }
                    assertActiveServes(4)
                    pending = 6
                    awaitPhase(6, "Ready")
                    assertActiveServes(4)
                    assertEquals(0, client.apps().deployments().inNamespace(namespace).list().items.size)
                    assertEquals(0, client.batch().v1().jobs().inNamespace(namespace).list().items.size)
                    val oldCandidatePod = client.pods().inNamespace(namespace).withName("serving-6").get()
                    val oldCandidateUid = oldCandidatePod.metadata.uid
                    client.pods().inNamespace(namespace).withName("serving-6").delete()
                    withTimeout(60_000) {
                        while (client.pods().inNamespace(namespace).withName("serving-6").get()?.metadata?.uid == oldCandidateUid) delay(250)
                    }
                    awaitPhase(6, "Ready")
                    assertNotEquals(oldCandidateUid, client.pods().inNamespace(namespace).withName("serving-6").get().metadata.uid)
                    assertActiveServes(4)
                    assertEquals(409, assertFailsWith<KubernetesClientException> {
                        client.resource(oldCandidatePod).lockResourceVersion().delete()
                    }.code)
                    assertActiveServes(6)
                    active = 6
                    pending = 7
                    requested = true
                    selectionRevision = 3
                    reconcile()
                    val stalled = client.genericKubernetesResources(SERVING_MODELS).inNamespace(namespace).withName("serving-7").get()
                    @Suppress("UNCHECKED_CAST")
                    val stalledSpec = stalled.additionalProperties["spec"] as MutableMap<String, Any?>
                    stalledSpec["podTemplate"] = Serialization.unmarshal(Serialization.asJson(template).replace("\"true\"", "\"sleep 3600\""), Map::class.java)
                    client.resource(stalled).update()
                    clock = clock.plusSeconds(11)
                    awaitPhase(7, "Downloading")
                    assertActiveServes(6)
                    val reportsBeforeStartupTimeout = reported.get()
                    clock = clock.plusSeconds(25)
                    selectionRevision = 4
                    reconcile()
                    assertEquals(reportsBeforeStartupTimeout, reported.get())
                    val retry = client.genericKubernetesResources(SERVING_MODELS).inNamespace(namespace).withName("serving-7").get()
                    assertEquals("4", retry.metadata.annotations[SELECTION_REVISION])
                    assertEquals(clock.toString(), retry.metadata.annotations[STARTUP_SINCE])
                    clock = clock.plusSeconds(31)
                    reconcile()
                    assertEquals(reportsBeforeStartupTimeout + 1, reported.get())
                    assertActiveServes(6)
                    pending = 8
                    requested = false
                    awaitPhase(8, "Ready")
                    assertActiveServes(6)
                    controller.close()
                } finally {
                    client.namespaces().withName(namespace).delete()
                }
            }
        }
    }
}
