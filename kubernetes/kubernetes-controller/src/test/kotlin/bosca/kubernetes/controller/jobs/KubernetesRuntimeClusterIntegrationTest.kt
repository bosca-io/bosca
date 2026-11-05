package bosca.kubernetes.controller.jobs

import bosca.db.withConnectionManager
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.jobs.KubernetesJobRequest
import bosca.kubernetes.jobs.KubernetesJobSecrets
import bosca.kubernetes.model.Cluster
import bosca.kubernetes.model.ClusterEnvironment
import bosca.kubernetes.model.KubernetesJobExecution
import bosca.kubernetes.model.KubernetesJobExecutionStatus
import bosca.kubernetes.repository.ClusterRepository
import bosca.kubernetes.repository.KubernetesJobExecutionRepository
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.security.encryption.EncryptionService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Opt-in API/resource gate; provider inference and a deployed Bosca instance are not required. */
@OptIn(InternalDI::class)
class KubernetesRuntimeClusterIntegrationTest {
    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json }
        mockkStatic("bosca.db.ConnectionPoolKt")
        coEvery { withConnectionManager<Any?>(any()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        unmockkAll()
    }

    @Test
    fun `native runtime uses job owned resources and reachable service in an isolated cluster`(): Unit = runBlocking {
        val kubeconfig = requireNotNull(System.getenv("KIT_KUBERNETES_KUBECONFIG")?.takeIf(String::isNotBlank))
        val image = requireNotNull(System.getenv("KIT_RUNTIME_IMAGE")?.takeIf(String::isNotBlank))
        val config = Config.fromKubeconfig(Files.readString(Path.of(kubeconfig)))
        require(config.currentContext?.name == "kind-bosca-kit-runtime-test") { "Use the disposable bosca-kit-runtime-test Kind cluster" }
        require(URI(config.masterUrl).host in setOf("127.0.0.1", "localhost")) { "The test requires a local cluster" }
        val namespace = "kit-contract-${UUID.random().toString().take(8)}"
        val sessionId = UUID.random()
        val dispatchId = UUID.random()
        val token = UUID.random().toString()
        val secrets = mapOf("BOSCA_RUNTIME_TOKEN" to token, "BOSCA_TOKEN" to "bsk_isolated_fixture",
            "BOSCA_RUNTIME_EXPIRES" to (System.currentTimeMillis() / 1000 + 600).toString())
        val encrypted = EncryptionService.Encrypted(byteArrayOf(1), byteArrayOf(2))
        val encryption = mockk<EncryptionService>()
        coEvery { encryption.decrypt(any(), dispatchId) } returns buildJsonObject {
            secrets.forEach { (key, value) -> put(key, value) }
        }.toString().encodeToByteArray()
        val queue = mockk<JobQueue>(relaxed = true)
        val queueFactory = mockk<JobQueueFactory> { every { create(any()) } returns queue }
        val pool = mockk<ClusterClientPool>()
        val clusters = mockk<ClusterRepository>()
        val executions = mockk<KubernetesJobExecutionRepository>(relaxed = true)
        val lock = mockk<DistributedLock>()
        val locks = mockk<DistributedLockFactory>()
        coEvery { locks.create(any()) } returns lock
        coEvery { lock.tryAcquire(any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true
        fun controller() = KubernetesJobController(encryption, pool, clusters, executions, queueFactory, locks, Json)
        var controller = controller()
        KubernetesClientBuilder().withConfig(config).build().use { client ->
            try {
                Files.newInputStream(Path.of("../../helm/bosca-kubernetes-controller/crds/jobprofiles.kubernetes.bosca.io.yaml")).use {
                    client.load(it).serverSideApply()
                }
                client.namespaces().resource(NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build()).create()
                val profileResource = Serialization.unmarshal(Files.readString(Path.of("../../ai/kit/runtime/job-profile.example.yaml")), GenericKubernetesResource::class.java)
                profileResource.metadata.namespace = namespace
                profileResource.metadata.name = namespace
                val original = JobProfile.from(profileResource.also { it.metadata.uid = UUID.random().toString() })
                val worker = original.podTemplate.spec.containers.single()
                worker.image = image
                worker.imagePullPolicy = "Never"
                worker.env = emptyList()
                // The gate uses a small reservation on its single disposable node.
                worker.resources.requests = mapOf("cpu" to Quantity("100m"), "memory" to Quantity("256Mi"), "ephemeral-storage" to Quantity("128Mi"))
                profileResource.additionalProperties["spec"] = (profileResource.additionalProperties.getValue("spec") as Map<*, *>)
                    .entries.associate { (key, value) -> key.toString() to value }.toMutableMap().also {
                    it["maxParallelism"] = 1
                    it["podTemplate"] = Serialization.unmarshal(Serialization.asJson(original.podTemplate), Map::class.java)
                }
                profileResource.metadata.uid = null
                val profile = JobProfile.from(client.genericKubernetesResources(API_VERSION, KIND).inNamespace(namespace).resource(profileResource).create())
                val request = KubernetesJobRequest(profile.name, "chat:$sessionId:1", environment = mapOf(
                    "BOSCA_CHAT_SESSION_ID" to sessionId.toString(), "BOSCA_RUNTIME_GENERATION" to "1", "BOSCA_ENDPOINT" to "http://bosca.invalid/graphql"),
                    secrets = KubernetesJobSecrets(dispatchId, Base64.getEncoder().encodeToString(encrypted.nonce),
                        Base64.getEncoder().encodeToString(encrypted.data), secrets.keys.toList()), servicePort = 8080)
                val clusterId = UUID.random()
                coEvery { clusters.list() } returns listOf(Cluster(clusterId, "isolated Kind", "kind", "local", ClusterEnvironment.DEVELOPMENT))
                coEvery { pool.get(clusterId) } returns client
                val states = mutableMapOf(dispatchId to KubernetesJobExecution(dispatchId, request.profile, request.idempotencyKey))
                val pending = ArrayDeque<Job>()
                val delayed = mutableListOf<Job>()
                fun item(id: UUID, value: KubernetesJobRequest) = mockk<Job> {
                    every { getId() } returns id
                    every { getDefinition() } returns Json.encodeToJsonElement(KubernetesJobRequest.serializer(), value)
                }
                coEvery { queue.dequeue(any()) } answers { pending.removeFirstOrNull() }
                coEvery { queue.checkin(any(), any()) } returns true
                coEvery { queue.enqueueLater(any(), any()) } answers { firstArg<Job>().also { delayed += it }.getId() }
                coEvery { executions.claimQueuedForPublication(any(), any()) } returns emptyList()
                coEvery { executions.getById(any()) } answers { states[firstArg()] }
                coEvery { executions.findCancellationRequested(any()) } answers { states.values.filter { it.status == KubernetesJobExecutionStatus.CANCEL_REQUESTED } }
                coEvery { executions.findMaterializedOrRunning(any()) } answers { states.values.filter { it.status in setOf(KubernetesJobExecutionStatus.MATERIALIZED, KubernetesJobExecutionStatus.RUNNING) } }
                coEvery { executions.markMaterialized(any(), any(), any(), any()) } answers {
                    val id = firstArg<UUID>()
                    states.getValue(id).copy(status = KubernetesJobExecutionStatus.MATERIALIZED, clusterId = arg(1), namespace = arg(2), jobName = arg(3), materializedAt = OffsetDateTime.now()).also { states[id] = it }
                }
                coEvery { executions.markRunning(any()) } answers {
                    val id = firstArg<UUID>()
                    states.getValue(id).copy(status = KubernetesJobExecutionStatus.RUNNING).also { states[id] = it }
                }
                coEvery { executions.recordImage(any(), any(), any()) } answers {
                    val id = firstArg<UUID>()
                    states[id] = states.getValue(id).copy(image = arg(1), imageId = arg(2))
                }
                coEvery { executions.markCancelled(any()) } answers {
                    val id = firstArg<UUID>()
                    states.getValue(id).copy(status = KubernetesJobExecutionStatus.CANCELLED).also { states[id] = it }
                }
                pending += item(dispatchId, request)
                val dispatchedAt = System.nanoTime()
                controller.reconcileAll()
                val desired = buildKubernetesJob(profile, request, dispatchId)
                val job = requireNotNull(client.batch().v1().jobs().inNamespace(namespace).withName(desired.metadata.name).get())
                val name = requireNotNull(job.metadata.name)
                controller.close()
                controller = controller()
                pending += item(dispatchId, request)
                controller.reconcileAll()
                assertEquals(job.metadata.uid, client.batch().v1().jobs().inNamespace(namespace).withName(name).get().metadata.uid)
                val nextId = UUID.random()
                val next = request.copy(idempotencyKey = "chat:$sessionId:2", environment = request.environment + ("BOSCA_RUNTIME_GENERATION" to "2"))
                states[nextId] = KubernetesJobExecution(nextId, next.profile, next.idempotencyKey)
                pending += item(nextId, next)
                controller.reconcileAll()
                assertEquals(1, delayed.size)
                assertEquals(1, client.batch().v1().jobs().inNamespace(namespace).list().items.size)
                assertEquals(KubernetesJobExecutionStatus.QUEUED, states.getValue(nextId).status)
                assertEquals(1, client.services().inNamespace(namespace).list().items.size)
                assertEquals(1, client.secrets().inNamespace(namespace).list().items.size)
                val secret = client.secrets().inNamespace(namespace).withName(name).get()
                assertTrue(secret.immutable)
                assertEquals(job.metadata.uid, secret.metadata.ownerReferences.single().uid)
                assertEquals(token, String(Base64.getDecoder().decode(secret.data.getValue("BOSCA_RUNTIME_TOKEN"))))
                assertFalse(Json.encodeToString(KubernetesJobRequest.serializer(), request).contains(token))
                assertTrue(desired.spec.template.spec.containers.single().env.filter { it.name in secrets }.all { it.value == null && it.valueFrom.secretKeyRef.name == name })
                val pod = withTimeout(180_000) {
                    while (true) {
                        val found = client.pods().inNamespace(namespace).withLabel(DISPATCH_ID_LABEL, dispatchId.toString()).list().items.singleOrNull()
                        val failure = found?.status?.containerStatuses.orEmpty().firstNotNullOfOrNull { it.state?.waiting }
                            ?.takeIf { it.reason in setOf("ErrImageNeverPull", "ErrImagePull", "ImagePullBackOff", "CreateContainerConfigError", "CrashLoopBackOff") }
                        check(failure == null) { "Runtime pod failed: ${failure?.reason}: ${failure?.message}" }
                        if (found?.status?.conditions.orEmpty().any { it.type == "Ready" && it.status == "True" }) return@withTimeout requireNotNull(found)
                        delay(500)
                    }
                    error("Unreachable")
                }
                val podSpec = pod.spec
                assertFalse(podSpec.automountServiceAccountToken)
                assertTrue(podSpec.securityContext.runAsNonRoot)
                val readyMillis = (System.nanoTime() - dispatchedAt) / 1_000_000
                assertEquals(1000L, podSpec.securityContext.runAsUser)
                assertTrue(podSpec.containers.single().securityContext.readOnlyRootFilesystem)
                assertEquals(listOf("ALL"), podSpec.containers.single().securityContext.capabilities.drop)
                assertTrue(podSpec.volumes.all { it.emptyDir != null })
                assertTrue(podSpec.volumes.all { it.emptyDir.sizeLimit != null })
                assertTrue(podSpec.containers.single().resources.limits.keys.containsAll(listOf("cpu", "memory", "ephemeral-storage")))
                assertEquals(image, podSpec.containers.single().image)
                assertFalse(pod.status.containerStatuses.single().imageID.isNullOrBlank())
                controller.reconcileAll()
                assertEquals(image, states.getValue(dispatchId).image)
                assertEquals(pod.status.containerStatuses.single().imageID, states.getValue(dispatchId).imageId)
                client.services().inNamespace(namespace).withName(name).portForward(8080).use { forward ->
                    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    fun call(path: String, auth: String = token, generation: String = "1", instance: String? = null, epoch: String = "1", post: Boolean = false): HttpResponse<String> {
                        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${forward.localPort}$path")).timeout(Duration.ofSeconds(30))
                            .header("Authorization", "Bearer $auth").header("X-Bosca-Generation", generation).header("X-Bosca-Epoch", epoch)
                        instance?.let { builder.header("X-Bosca-Instance", it) }
                        if (post) builder.POST(HttpRequest.BodyPublishers.noBody())
                        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                    }
                    assertEquals(200, call("/readyz").statusCode())
                    assertEquals(401, call("/v1/claim", auth = "wrong", post = true).statusCode())
                    assertEquals(401, call("/v1/claim", generation = "2", post = true).statusCode())
                    val claimed = call("/v1/claim", post = true)
                    assertEquals(200, claimed.statusCode(), claimed.body())
                    val status = Json.parseToJsonElement(claimed.body()).jsonObject
                    assertEquals("PREPARED", status.getValue("phase").jsonPrimitive.content)
                    val instance = status.getValue("instanceId").jsonPrimitive.content
                    assertEquals(409, call("/v1/runtime", instance = "replaced").statusCode())
                    assertEquals(409, call("/v1/runtime", instance = instance, epoch = "2").statusCode())
                    assertEquals(200, call("/v1/runtime", instance = instance).statusCode())
                }
                // Probe from a second pod and allow the Service/DNS controllers to publish readiness.
                val probeCode = """
                    let last;
                    for (let attempt = 0; attempt < 60; attempt++) {
                        try {
                            const response = await fetch('http://$name.$namespace.svc:8080/readyz', {signal: AbortSignal.timeout(1000)});
                            if (response.status === 200) process.exit(0);
                            last = 'Service HTTP ' + response.status;
                        } catch (error) { last = error; }
                        await new Promise(resolve => setTimeout(resolve, 250));
                    }
                    console.error(last);
                    process.exit(1);
                """.trimIndent()
                val probe = PodBuilder(pod).withNewMetadata().withName("route-probe").endMetadata().withStatus(null)
                    .editSpec().withNodeName(null).withVolumes(emptyList()).withContainers(ContainerBuilder(worker)
                        .withName("probe").withEnv(emptyList()).withVolumeMounts(emptyList())
                        .withCommand("node", "--input-type=module", "-e", probeCode)
                        .withArgs(emptyList()).withReadinessProbe(null).withLivenessProbe(null).build()).endSpec().build()
                client.pods().inNamespace(namespace).resource(probe).create()
                val routed = client.pods().inNamespace(namespace).withName("route-probe").waitUntilCondition({ it?.status?.phase in setOf("Succeeded", "Failed") }, 90, TimeUnit.SECONDS)
                assertEquals("Succeeded", routed.status.phase, client.pods().inNamespace(namespace).withName("route-probe").getLog())
                // Wrong owner identity must not adopt or alter this generation's Secret/Service.
                val stale = io.fabric8.kubernetes.api.model.batch.v1.JobBuilder(job).editMetadata().withUid(UUID.random().toString()).endMetadata().build()
                assertFailsWith<IllegalStateException> { controller.ensureWorkloadResources(client, stale, request, dispatchId) }
                assertEquals(job.metadata.uid, client.services().inNamespace(namespace).withName(name).get().metadata.ownerReferences.single().uid)
                println("Kubernetes dispatch to observed readiness: $readyMillis ms; worker imageID=${pod.status.containerStatuses.single().imageID}")
                states[dispatchId] = states.getValue(dispatchId).copy(status = KubernetesJobExecutionStatus.CANCEL_REQUESTED)
                pending.addAll(delayed)
                delayed.clear()
                controller.reconcileAll()
                val replacementName = kubernetesJobName(next.profile, next.idempotencyKey)
                val replacementUid = client.batch().v1().jobs().inNamespace(namespace).withName(replacementName).get().metadata.uid
                withTimeout(90_000) {
                    while (client.services().inNamespace(namespace).withName(name).get() != null || client.secrets().inNamespace(namespace).withName(name).get() != null ||
                        client.pods().inNamespace(namespace).withLabel(DISPATCH_ID_LABEL, dispatchId.toString()).list().items.isNotEmpty()) delay(500)
                }
                states[dispatchId] = states.getValue(dispatchId).copy(status = KubernetesJobExecutionStatus.CANCEL_REQUESTED)
                controller.reconcileAll()
                assertEquals(replacementUid, client.batch().v1().jobs().inNamespace(namespace).withName(replacementName).get().metadata.uid)
                assertEquals(KubernetesJobExecutionStatus.CANCELLED, states.getValue(dispatchId).status)
                states[nextId] = states.getValue(nextId).copy(status = KubernetesJobExecutionStatus.CANCEL_REQUESTED)
                controller.reconcileAll()
                withTimeout(90_000) {
                    while (client.batch().v1().jobs().inNamespace(namespace).list().items.isNotEmpty() ||
                        client.services().inNamespace(namespace).list().items.isNotEmpty() || client.secrets().inNamespace(namespace).list().items.isNotEmpty() ||
                        client.pods().inNamespace(namespace).withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE).list().items.isNotEmpty()) delay(500)
                }
            } finally {
                controller.close()
                client.batch().v1().jobs().inNamespace(namespace).list().items.forEach { job ->
                    client.batch().v1().jobs().inNamespace(namespace).withName(job.metadata.name).edit { current ->
                        io.fabric8.kubernetes.api.model.batch.v1.JobBuilder(current).editMetadata().withFinalizers(emptyList()).endMetadata().build()
                    }
                }
                client.namespaces().withName(namespace).delete()
            }
        }
    }
}
