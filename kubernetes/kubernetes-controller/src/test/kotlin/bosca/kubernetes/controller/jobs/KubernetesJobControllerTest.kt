package bosca.kubernetes.controller.jobs

import bosca.db.withConnectionManager
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.jobs.KubernetesJobQueueNames
import bosca.kubernetes.jobs.KubernetesJobRequest
import bosca.kubernetes.model.Cluster
import bosca.kubernetes.model.ClusterEnvironment
import bosca.kubernetes.model.KubernetesJobExecution
import bosca.kubernetes.model.KubernetesJobExecutionStatus
import bosca.kubernetes.repository.ClusterRepository
import bosca.kubernetes.repository.KubernetesJobExecutionRepository
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ContainerStateBuilder
import io.fabric8.kubernetes.api.model.ContainerStateWaitingBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodConditionBuilder
import io.fabric8.kubernetes.api.model.PodListBuilder
import io.fabric8.kubernetes.api.model.PodStatusBuilder
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobListBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobConditionBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.NamespaceableResource
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import io.fabric8.kubernetes.client.utils.Serialization
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

@OptIn(ExperimentalUuidApi::class, InternalDI::class)
class KubernetesJobControllerTest {

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json }
        mockkStatic("bosca.db.ConnectionPoolKt")
        coEvery { withConnectionManager<Any?>(any()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        unmockkStatic("bosca.db.ConnectionPoolKt")
        io.mockk.unmockkAll()
    }

    @Test
    fun `reconcile consumes one queue item and creates one kubernetes job`() = runTest {
        val clusterId = UUID.random()
        val dispatchId = UUID.random()
        val resource = resource()
        val request = KubernetesJobRequest(
            profile = "android",
            idempotencyKey = "ci-job-123-attempt-1",
            environment = mapOf("BOSCA_CI_JOB_ID" to "123"),
        )
        val queueItem = mockk<Job>()
        val client = mockk<KubernetesClient>(relaxed = true)
        val pool = mockk<ClusterClientPool>()
        val clusters = mockk<ClusterRepository>()
        val executions = mockk<KubernetesJobExecutionRepository>(relaxed = true)
        val queueFactory = mockk<JobQueueFactory>()
        val queue = mockk<JobQueue>()
        val lockFactory = mockk<DistributedLockFactory>()
        val lock = mockk<DistributedLock>()
        val statusResource = mockk<NamespaceableResource<GenericKubernetesResource>>()
        val createdJobs = mutableListOf<io.fabric8.kubernetes.api.model.batch.v1.Job>()

        every { queueFactory.create(KubernetesJobQueueNames.queue) } returns queue
        coEvery { clusters.list() } returns listOf(
            Cluster(
                id = clusterId,
                name = "test",
                provider = "kind",
                region = "local",
                environment = ClusterEnvironment.DEVELOPMENT,
            )
        )
        coEvery { pool.get(clusterId) } returns client
        coEvery { executions.findCancellationRequested(any()) } returns emptyList()
        coEvery { executions.getById(dispatchId) } returns null
        coEvery {
            executions.create(dispatchId, request.profile, request.idempotencyKey, any())
        } returns KubernetesJobExecution(dispatchId, request.profile, request.idempotencyKey)
        coEvery {
            executions.markMaterialized(dispatchId, clusterId, "workers", any())
        } answers {
            KubernetesJobExecution(
                dispatchId,
                request.profile,
                request.idempotencyKey,
                clusterId = clusterId,
                namespace = "workers",
                jobName = arg(3),
            )
        }
        every {
            client.genericKubernetesResources(any<ResourceDefinitionContext>())
                .inAnyNamespace()
                .list()
        } returns GenericKubernetesResourceList().also { it.items = listOf(resource) }
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .withLabel(PROFILE_LABEL, "android")
                .withLabel(PROFILE_UID_LABEL, "profile-uid")
                .list()
        } returns JobListBuilder().build()
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(any())
                .get()
        } returns null
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .resource(capture(createdJobs))
                .create()
        } answers { createdJobs.last() }
        every { client.resource(resource) } returns statusResource
        every { statusResource.patchStatus() } returns resource
        coEvery { queue.dequeue(any()) } returns queueItem andThen null
        every { queueItem.getId() } returns dispatchId
        every { queueItem.getDefinition() } returns Json.encodeToJsonElement(request)
        coEvery { queue.checkin(queueItem, any()) } returns true
        coEvery { queue.markComplete(queueItem) } returns Unit
        coEvery { queue.releaseJobLock(queueItem) } returns Unit
        coEvery { lockFactory.create(KubernetesJobQueueNames.dispatch) } returns lock
        coEvery { lock.tryAcquire(any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true

        KubernetesJobController(
            pool,
            clusters,
            executions,
            queueFactory,
            lockFactory,
            Json,
        ).reconcileAll()

        assertEquals(1, createdJobs.size)
        assertEquals("123", createdJobs.single().spec.template.spec.containers.single()
            .env.first { it.name == "BOSCA_CI_JOB_ID" }.value)
        assertEquals(1, resource.additionalProperties.statusValue("activeJobs"))
        coVerify(exactly = 2) { queue.checkin(queueItem, 300_000L) }
        coVerify(exactly = 1) { queue.markComplete(queueItem) }
        verify(exactly = 1) { statusResource.patchStatus() }
    }

    @Test
    fun `failed pod count does not release capacity before the job is terminal`() {
        val retrying = JobBuilder()
            .withStatus(JobStatusBuilder().withFailed(1).build())
            .build()
        val terminal = JobBuilder()
            .withStatus(
                JobStatusBuilder()
                    .withConditions(
                        JobConditionBuilder()
                            .withType("Failed")
                            .withStatus("True")
                            .build()
                    )
                    .build()
            )
            .build()

        assertFalse(retrying.isTerminal())
        assertTrue(terminal.isTerminal())
    }

    @Test
    fun `a full profile at the queue head does not block another profile`() = runTest {
        val clusterId = UUID.random()
        val androidProfile = resource(name = "android", uid = "android-uid", maxParallelism = 1)
        val gpuProfile = resource(name = "gpu", uid = "gpu-uid", maxParallelism = 1)
        val androidItem = mockk<Job>()
        val gpuItem = mockk<Job>()
        val client = mockk<KubernetesClient>(relaxed = true)
        val pool = mockk<ClusterClientPool>()
        val clusters = mockk<ClusterRepository>()
        val executions = mockk<KubernetesJobExecutionRepository>(relaxed = true)
        val queueFactory = mockk<JobQueueFactory>()
        val queue = mockk<JobQueue>()
        val lockFactory = mockk<DistributedLockFactory>()
        val lock = mockk<DistributedLock>()
        val createdJobs = mutableListOf<io.fabric8.kubernetes.api.model.batch.v1.Job>()

        every { queueFactory.create(KubernetesJobQueueNames.queue) } returns queue
        coEvery { clusters.list() } returns listOf(
            Cluster(
                id = clusterId,
                name = "test",
                provider = "kind",
                region = "local",
                environment = ClusterEnvironment.DEVELOPMENT,
            )
        )
        coEvery { pool.get(clusterId) } returns client
        coEvery { executions.findCancellationRequested(any()) } returns emptyList()
        every {
            client.genericKubernetesResources(any<ResourceDefinitionContext>())
                .inAnyNamespace()
                .list()
        } returns GenericKubernetesResourceList().also {
            it.items = listOf(androidProfile, gpuProfile)
        }
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .withLabel(PROFILE_LABEL, "android")
                .withLabel(PROFILE_UID_LABEL, "android-uid")
                .list()
        } returns JobListBuilder().withItems(JobBuilder().build()).build()
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .withLabel(PROFILE_LABEL, "gpu")
                .withLabel(PROFILE_UID_LABEL, "gpu-uid")
                .list()
        } returns JobListBuilder().build()
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(any())
                .get()
        } returns null
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .resource(capture(createdJobs))
                .create()
        } answers { createdJobs.last() }
        every { client.resource(any<GenericKubernetesResource>()).patchStatus() } returns androidProfile
        coEvery { queue.dequeue(any()) } returns androidItem andThen gpuItem andThen null
        val androidDispatchId = UUID.random()
        val gpuDispatchId = UUID.random()
        every { androidItem.getId() } returns androidDispatchId
        every { gpuItem.getId() } returns gpuDispatchId
        every { androidItem.getDefinition() } returns Json.encodeToJsonElement(
            KubernetesJobRequest("android", "android-1")
        )
        every { gpuItem.getDefinition() } returns Json.encodeToJsonElement(
            KubernetesJobRequest("gpu", "gpu-1")
        )
        coEvery { queue.checkin(any(), any()) } returns true
        coEvery { executions.getById(androidDispatchId) } returns null
        coEvery {
            executions.create(androidDispatchId, "android", "android-1", any())
        } returns KubernetesJobExecution(androidDispatchId, "android", "android-1")
        coEvery { executions.getById(gpuDispatchId) } returns null
        coEvery {
            executions.create(gpuDispatchId, "gpu", "gpu-1", any())
        } returns KubernetesJobExecution(gpuDispatchId, "gpu", "gpu-1")
        coEvery {
            executions.markMaterialized(gpuDispatchId, clusterId, "workers", any())
        } answers {
            KubernetesJobExecution(
                gpuDispatchId,
                "gpu",
                "gpu-1",
                clusterId = clusterId,
                namespace = "workers",
                jobName = arg(3),
            )
        }
        coEvery { queue.enqueueLater(androidItem, any()) } returns androidItem.getId()
        coEvery { queue.markComplete(gpuItem) } returns Unit
        coEvery { queue.releaseJobLock(any()) } returns Unit
        coEvery { lockFactory.create(KubernetesJobQueueNames.dispatch) } returns lock
        coEvery { lock.tryAcquire(any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true

        KubernetesJobController(
            pool,
            clusters,
            executions,
            queueFactory,
            lockFactory,
            Json,
        ).reconcileAll()

        assertEquals(1, createdJobs.size)
        assertEquals("gpu", createdJobs.single().metadata.labels[PROFILE_LABEL])
        coVerify(exactly = 1) { queue.enqueueLater(androidItem, any()) }
        coVerify(exactly = 1) { queue.markComplete(gpuItem) }
        coVerify(exactly = 0) { queue.markComplete(androidItem) }
    }

    @Test
    fun `lost queue ownership does not mutate or dispatch the item`() = runTest {
        val clusterId = UUID.random()
        val queueItem = mockk<Job>()
        val client = mockk<KubernetesClient>(relaxed = true)
        val pool = mockk<ClusterClientPool>()
        val clusters = mockk<ClusterRepository>()
        val executions = mockk<KubernetesJobExecutionRepository>(relaxed = true)
        val queueFactory = mockk<JobQueueFactory>()
        val queue = mockk<JobQueue>()
        val lockFactory = mockk<DistributedLockFactory>()
        val lock = mockk<DistributedLock>()
        val profileResource = resource()
        val statusResource = mockk<NamespaceableResource<GenericKubernetesResource>>()

        every { queueFactory.create(KubernetesJobQueueNames.queue) } returns queue
        coEvery { clusters.list() } returns listOf(
            Cluster(
                id = clusterId,
                name = "test",
                provider = "kind",
                region = "local",
                environment = ClusterEnvironment.DEVELOPMENT,
            )
        )
        coEvery { pool.get(clusterId) } returns client
        coEvery { executions.findCancellationRequested(any()) } returns emptyList()
        every {
            client.genericKubernetesResources(any<ResourceDefinitionContext>())
                .inAnyNamespace()
                .list()
        } returns GenericKubernetesResourceList().also { it.items = listOf(profileResource) }
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .withLabel(PROFILE_LABEL, "android")
                .withLabel(PROFILE_UID_LABEL, "profile-uid")
                .list()
        } returns JobListBuilder().build()
        every { client.resource(profileResource) } returns statusResource
        every { statusResource.patchStatus() } returns profileResource
        coEvery { queue.dequeue(any()) } returns queueItem andThen null
        every { queueItem.getId() } returns UUID.random()
        coEvery { queue.checkin(queueItem, any()) } returns false
        coEvery { queue.releaseJobLock(queueItem) } returns Unit
        coEvery { lockFactory.create(KubernetesJobQueueNames.dispatch) } returns lock
        coEvery { lock.tryAcquire(any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true

        KubernetesJobController(
            pool,
            clusters,
            executions,
            queueFactory,
            lockFactory,
            Json,
        ).reconcileAll()

        coVerify(exactly = 0) { queue.markComplete(any()) }
        coVerify(exactly = 0) { queue.markFailed(any(), any(), any()) }
        coVerify(exactly = 0) { queue.enqueueLater(any(), any()) }
        verify(exactly = 0) { queueItem.getDefinition() }
        coVerify(exactly = 1) { queue.releaseJobLock(queueItem) }
    }

    @Test
    fun `dispatch lock contention leaves queue and outbox untouched`() = runTest {
        val fixture = emptyProfileFixture()
        coEvery { fixture.lock.tryAcquire(any()) } returns false

        fixture.controller.reconcileAll()

        coVerify(exactly = 0) { fixture.executions.claimQueuedForPublication(any(), any()) }
        coVerify(exactly = 0) { fixture.queue.dequeue(any()) }
        coVerify(exactly = 0) { fixture.lock.release() }
    }

    @Test
    fun `dispatch lock release failure does not discard completed reconciliation`() = runTest {
        val fixture = emptyProfileFixture()
        coEvery { fixture.lock.release() } throws IllegalStateException("lock service unavailable")

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) { fixture.lock.release() }
    }

    @Test
    fun `background controller start is idempotent and close is repeatable`() = runBlocking {
        val fixture = emptyProfileFixture(intervalMillis = 1)
        coEvery { fixture.clusters.list() } throws
            IllegalStateException("one failed cycle") andThen emptyList()

        fixture.controller.start()
        fixture.controller.start()
        delay(50)
        fixture.controller.close()
        fixture.controller.close()

        coVerify(atLeast = 1) { fixture.clusters.list() }
    }

    @Test
    fun `outbox recovery uses idempotent queue publication`() = runTest {
        val dispatchId = UUID.random()
        val request = KubernetesJobRequest("android", "ci-recovery")
        val execution = KubernetesJobExecution(
            dispatchId = dispatchId,
            profile = request.profile,
            idempotencyKey = request.idempotencyKey,
            request = Json.encodeToJsonElement(request),
            createdAt = bosca.serialization.OffsetDateTime.now().minusSeconds(120),
        )
        val pool = mockk<ClusterClientPool>()
        val clusters = mockk<ClusterRepository>()
        val executions = mockk<KubernetesJobExecutionRepository>(relaxed = true)
        val queueFactory = mockk<JobQueueFactory>()
        val queue = mockk<JobQueue>()
        val lockFactory = mockk<DistributedLockFactory>()
        val lock = mockk<DistributedLock>()
        val recoveredJob = slot<Job>()

        every { queueFactory.create(KubernetesJobQueueNames.queue) } returns queue
        coEvery { clusters.list() } returns emptyList()
        coEvery { executions.claimQueuedForPublication(any(), 100) } returns listOf(execution)
        coEvery { executions.findCancellationRequested(any()) } returns emptyList()
        coEvery { executions.findMaterializedOrRunning(any()) } returns emptyList()
        coEvery { queue.enqueueIfAbsent(capture(recoveredJob)) } returns dispatchId
        coEvery { queue.dequeue(any()) } returns null
        coEvery { lockFactory.create(KubernetesJobQueueNames.dispatch) } returns lock
        coEvery { lock.tryAcquire(any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true

        KubernetesJobController(
            pool,
            clusters,
            executions,
            queueFactory,
            lockFactory,
            Json,
        ).reconcileAll()

        assertEquals(dispatchId, recoveredJob.captured.getId())
        assertEquals(Json.encodeToJsonElement(request), recoveredJob.captured.getDefinition())
        coVerify(exactly = 1) { queue.enqueueIfAbsent(any()) }
        coVerify(exactly = 0) { queue.enqueue(any()) }
        coVerify(exactly = 1) { executions.markPublished(dispatchId) }
    }

    @Test
    fun `missing profile is delayed even when the cluster has zero aggregate capacity`() = runTest {
        val fixture = emptyProfileFixture()
        val dispatchId = UUID.random()
        val request = KubernetesJobRequest("missing", "training-1")
        val item = mockk<Job>()
        val execution = KubernetesJobExecution(
            dispatchId = dispatchId,
            profile = request.profile,
            idempotencyKey = request.idempotencyKey,
            request = Json.encodeToJsonElement(request),
        )
        every { item.getId() } returns dispatchId
        every { item.getDefinition() } returns Json.encodeToJsonElement(request)
        coEvery { fixture.queue.dequeue(any()) } returns item andThen null
        coEvery { fixture.queue.checkin(item, any()) } returns true
        coEvery { fixture.queue.enqueueLater(item, any()) } returns dispatchId
        coEvery { fixture.queue.releaseJobLock(item) } returns Unit
        coEvery { fixture.executions.getById(dispatchId) } returns execution

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) { fixture.executions.touchQueued(dispatchId) }
        coVerify(exactly = 1) { fixture.queue.enqueueLater(item, any()) }
        coVerify(exactly = 0) { fixture.queue.markComplete(item) }
    }

    @Test
    fun `published request that exhausted its dispatch wait fails without retry`() = runTest {
        val fixture = emptyProfileFixture()
        val dispatchId = UUID.random()
        val request = KubernetesJobRequest(
            profile = "missing",
            idempotencyKey = "training-expired",
            dispatchWaitTimeoutSeconds = 1,
        )
        val item = mockk<Job>()
        val execution = KubernetesJobExecution(
            dispatchId = dispatchId,
            profile = request.profile,
            idempotencyKey = request.idempotencyKey,
            request = Json.encodeToJsonElement(request),
            createdAt = bosca.serialization.OffsetDateTime.now().minusSeconds(2),
        )
        every { item.getId() } returns dispatchId
        every { item.getDefinition() } returns Json.encodeToJsonElement(request)
        coEvery { fixture.queue.dequeue(any()) } returns item andThen null
        coEvery { fixture.queue.checkin(item, any()) } returns true
        coEvery { fixture.queue.markFailed(item, any(), false) } returns Unit
        coEvery { fixture.queue.releaseJobLock(item) } returns Unit
        coEvery { fixture.executions.getById(dispatchId) } returns execution
        coEvery { fixture.executions.markFailed(dispatchId, any()) } returns execution.copy(
            status = KubernetesJobExecutionStatus.FAILED,
        )

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) { fixture.queue.markFailed(item, any(), false) }
        coVerify(exactly = 1) {
            fixture.executions.markFailed(
                dispatchId,
                match { it.contains("not materialized within 1 seconds") },
            )
        }
        coVerify(exactly = 0) { fixture.queue.enqueueLater(item, any()) }
    }

    @Test
    fun `expired unpublished outbox record is cancelled and failed`() = runTest {
        val fixture = emptyProfileFixture()
        val dispatchId = UUID.random()
        val request = KubernetesJobRequest(
            profile = "missing",
            idempotencyKey = "training-expired",
            dispatchWaitTimeoutSeconds = 1,
        )
        val execution = KubernetesJobExecution(
            dispatchId = dispatchId,
            profile = request.profile,
            idempotencyKey = request.idempotencyKey,
            request = Json.encodeToJsonElement(request),
            createdAt = bosca.serialization.OffsetDateTime.now().minusSeconds(2),
        )
        coEvery {
            fixture.executions.claimQueuedForPublication(any(), 100)
        } returns listOf(execution)
        coEvery { fixture.queue.markCancelled(dispatchId) } returns Unit
        coEvery { fixture.executions.markFailed(dispatchId, any()) } returns execution.copy(
            status = KubernetesJobExecutionStatus.FAILED,
        )

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) { fixture.queue.markCancelled(dispatchId) }
        coVerify(exactly = 1) { fixture.executions.markFailed(dispatchId, any()) }
        coVerify(exactly = 0) { fixture.queue.enqueueIfAbsent(any()) }
    }

    @Test
    fun `undecodable outbox record fails durably without queue publication`() = runTest {
        val fixture = emptyProfileFixture()
        val dispatchId = UUID.random()
        val execution = KubernetesJobExecution(
            dispatchId = dispatchId,
            profile = "training",
            idempotencyKey = "invalid-request",
            request = JsonPrimitive("not an object"),
            createdAt = bosca.serialization.OffsetDateTime.now().minusSeconds(120),
        )
        coEvery {
            fixture.executions.claimQueuedForPublication(any(), 100)
        } returns listOf(execution)
        coEvery { fixture.executions.markFailed(dispatchId, any()) } returns execution.copy(
            status = KubernetesJobExecutionStatus.FAILED,
        )

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) {
            fixture.executions.markFailed(
                dispatchId,
                match { it.startsWith("Persisted Kubernetes Job request could not be decoded") },
            )
        }
        coVerify(exactly = 0) { fixture.queue.enqueueIfAbsent(any()) }
        coVerify(exactly = 0) { fixture.queue.markCancelled(any()) }
    }

    @Test
    fun `poisoned queue records retry and durably fail after queue exhaustion`() = runTest {
        val fixture = emptyProfileFixture()
        val retryingId = UUID.random()
        val exhaustedId = UUID.random()
        val retrying = mockk<Job>()
        val exhausted = mockk<Job>()
        every { retrying.getId() } returns retryingId
        every { exhausted.getId() } returns exhaustedId
        every { retrying.getDefinition() } throws IllegalArgumentException("invalid payload")
        every { exhausted.getDefinition() } throws IllegalArgumentException()
        every { retrying.isFullyComplete() } returns false
        every { exhausted.isFullyComplete() } returns true
        coEvery { fixture.queue.dequeue(any()) } returns retrying andThen exhausted andThen null
        coEvery { fixture.queue.checkin(any(), any()) } returns true

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) { fixture.queue.markFailed(retrying, any(), true) }
        coVerify(exactly = 1) { fixture.queue.markFailed(exhausted, any(), true) }
        coVerify(exactly = 0) { fixture.executions.markFailed(retryingId, any()) }
        coVerify(exactly = 1) {
            fixture.executions.markFailed(
                exhaustedId,
                match { it.startsWith("Kubernetes Job dispatch failed after exhausting retries") },
            )
        }
        coVerify(exactly = 1) { fixture.queue.releaseJobLock(retrying) }
        coVerify(exactly = 1) { fixture.queue.releaseJobLock(exhausted) }
    }

    @Test
    fun `controller cancellation requeues the owned item before propagating`() = runTest {
        val fixture = emptyProfileFixture()
        val dispatchId = UUID.random()
        val item = mockk<Job>()
        every { item.getId() } returns dispatchId
        every { item.getDefinition() } throws kotlinx.coroutines.CancellationException("shutdown")
        coEvery { fixture.queue.dequeue(any()) } returns item
        coEvery { fixture.queue.checkin(item, any()) } returns true
        coEvery { fixture.queue.enqueueLater(item, any()) } returns dispatchId

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            fixture.controller.reconcileAll()
        }

        coVerify(exactly = 1) { fixture.queue.enqueueLater(item, any()) }
        coVerify(exactly = 1) { fixture.queue.releaseJobLock(item) }
        coVerify(exactly = 1) { fixture.lock.release() }
    }

    @Test
    fun `controller cancellation survives a failed shutdown requeue`() = runTest {
        val fixture = emptyProfileFixture()
        val item = mockk<Job>()
        every { item.getId() } returns UUID.random()
        every { item.getDefinition() } throws kotlinx.coroutines.CancellationException("shutdown")
        coEvery { fixture.queue.dequeue(any()) } returns item
        coEvery { fixture.queue.checkin(item, any()) } returns true
        coEvery { fixture.queue.enqueueLater(item, any()) } throws
            IllegalStateException("queue unavailable")

        val failure = assertFailsWith<kotlinx.coroutines.CancellationException> {
            fixture.controller.reconcileAll()
        }

        assertEquals("shutdown", failure.message)
        coVerify(exactly = 1) { fixture.queue.enqueueLater(item, any()) }
        coVerify(exactly = 1) { fixture.queue.releaseJobLock(item) }
    }

    @Test
    fun `cancellation deletes an already materialized kubernetes job`() = runTest {
        val clusterId = UUID.random()
        val dispatchId = UUID.random()
        val profileResource = resource()
        val jobName = "android-existing"
        val existing = JobBuilder()
            .withMetadata(
                ObjectMetaBuilder()
                    .withName(jobName)
                    .withNamespace("workers")
                    .addToAnnotations(DISPATCH_ID_ANNOTATION, dispatchId.toString())
                    .addToAnnotations(IDEMPOTENCY_KEY_ANNOTATION, "ci-1")
                    .build()
            )
            .build()
        val cancellation = KubernetesJobExecution(
            dispatchId = dispatchId,
            profile = "android",
            idempotencyKey = "ci-1",
            status = KubernetesJobExecutionStatus.CANCEL_REQUESTED,
            clusterId = clusterId,
            namespace = "workers",
            jobName = jobName,
            materializedAt = bosca.serialization.OffsetDateTime.now().minusSeconds(1),
        )
        val client = mockk<KubernetesClient>(relaxed = true)
        val pool = mockk<ClusterClientPool>()
        val clusters = mockk<ClusterRepository>()
        val executions = mockk<KubernetesJobExecutionRepository>(relaxed = true)
        val queueFactory = mockk<JobQueueFactory>()
        val queue = mockk<JobQueue>()
        val lockFactory = mockk<DistributedLockFactory>()
        val lock = mockk<DistributedLock>()

        every { queueFactory.create(KubernetesJobQueueNames.queue) } returns queue
        coEvery { clusters.list() } returns listOf(testCluster(clusterId))
        coEvery { pool.get(clusterId) } returns client
        every {
            client.genericKubernetesResources(any<ResourceDefinitionContext>())
                .inAnyNamespace()
                .list()
        } returns GenericKubernetesResourceList().also { it.items = listOf(profileResource) }
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .withLabel(PROFILE_LABEL, "android")
                .withLabel(PROFILE_UID_LABEL, "profile-uid")
                .list()
        } returns JobListBuilder().withItems(existing).build()
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                .delete()
        } returns emptyList()
        every { client.resource(profileResource).patchStatus() } returns profileResource
        coEvery { executions.findCancellationRequested(1_000) } returns listOf(cancellation)
        coEvery { executions.markCancelled(dispatchId) } returns cancellation.copy(
            status = KubernetesJobExecutionStatus.CANCELLED,
        )
        coEvery { queue.markCancelled(dispatchId) } returns Unit
        coEvery { queue.dequeue(any()) } returns null
        coEvery { lockFactory.create(KubernetesJobQueueNames.dispatch) } returns lock
        coEvery { lock.tryAcquire(any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true

        KubernetesJobController(
            pool,
            clusters,
            executions,
            queueFactory,
            lockFactory,
            Json,
        ).reconcileAll()

        verify(atLeast = 1) {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                .delete()
        }
        coVerifyOrder {
            queue.markCancelled(dispatchId)
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                .delete()
            executions.markCancelled(dispatchId)
        }
        coVerify(exactly = 1) { queue.markCancelled(dispatchId) }
        coVerify(exactly = 1) { executions.markCancelled(dispatchId) }
        assertEquals(0, profileResource.additionalProperties.statusValue("activeJobs"))
    }

    @Test
    fun `cancellation falls back to persisted Kubernetes identity when profile is absent`() = runTest {
        val fixture = emptyProfileFixture()
        val clusterId = UUID.random()
        val dispatchId = UUID.random()
        val jobName = "training-persisted"
        val client = mockk<KubernetesClient>(relaxed = true)
        val job = JobBuilder()
            .withMetadata(jobMetadata(jobName))
            .build()
        val cancellation = KubernetesJobExecution(
            dispatchId = dispatchId,
            profile = "training",
            idempotencyKey = "persisted",
            status = KubernetesJobExecutionStatus.CANCEL_REQUESTED,
            clusterId = clusterId,
            namespace = "workers",
            jobName = jobName,
            materializedAt = bosca.serialization.OffsetDateTime.now(),
        )
        coEvery {
            fixture.executions.findCancellationRequested(any())
        } returns listOf(cancellation)
        coEvery { fixture.pool.get(clusterId) } returns client
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .get()
        } returns job
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                .delete()
        } returns emptyList()
        coEvery { fixture.executions.markCancelled(dispatchId) } returns cancellation.copy(
            status = KubernetesJobExecutionStatus.CANCELLED,
            finishedAt = bosca.serialization.OffsetDateTime.now(),
        )

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) { fixture.queue.markCancelled(dispatchId) }
        coVerify(exactly = 1) { fixture.pool.get(clusterId) }
        coVerify(exactly = 1) { fixture.executions.markCancelled(dispatchId) }
    }

    @Test
    fun `cancellation observed before dispatch prevents kubernetes job creation`() = runTest {
        val clusterId = UUID.random()
        val dispatchId = UUID.random()
        val profileResource = resource()
        val request = KubernetesJobRequest("android", "ci-cancel-race")
        val queueItem = mockk<Job>()
        val client = mockk<KubernetesClient>(relaxed = true)
        val pool = mockk<ClusterClientPool>()
        val clusters = mockk<ClusterRepository>()
        val executions = mockk<KubernetesJobExecutionRepository>(relaxed = true)
        val queueFactory = mockk<JobQueueFactory>()
        val queue = mockk<JobQueue>()
        val lockFactory = mockk<DistributedLockFactory>()
        val lock = mockk<DistributedLock>()
        val createdJobs = mutableListOf<io.fabric8.kubernetes.api.model.batch.v1.Job>()
        val cancellation = KubernetesJobExecution(
            dispatchId = dispatchId,
            profile = request.profile,
            idempotencyKey = request.idempotencyKey,
            status = KubernetesJobExecutionStatus.CANCEL_REQUESTED,
        )

        every { queueFactory.create(KubernetesJobQueueNames.queue) } returns queue
        coEvery { clusters.list() } returns listOf(testCluster(clusterId))
        coEvery { pool.get(clusterId) } returns client
        every {
            client.genericKubernetesResources(any<ResourceDefinitionContext>())
                .inAnyNamespace()
                .list()
        } returns GenericKubernetesResourceList().also { it.items = listOf(profileResource) }
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .withLabel(PROFILE_LABEL, "android")
                .withLabel(PROFILE_UID_LABEL, "profile-uid")
                .list()
        } returns JobListBuilder().build()
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .resource(capture(createdJobs))
                .create()
        } answers { createdJobs.last() }
        every { client.resource(profileResource).patchStatus() } returns profileResource
        coEvery { executions.findCancellationRequested(any()) } returns listOf(cancellation)
        coEvery { executions.markCancelled(dispatchId) } returns cancellation.copy(
            status = KubernetesJobExecutionStatus.CANCELLED,
        )
        coEvery { executions.getById(dispatchId) } returns cancellation.copy(
            status = KubernetesJobExecutionStatus.CANCELLED,
        )
        coEvery { queue.markCancelled(dispatchId) } returns Unit
        coEvery { queue.dequeue(any()) } returns queueItem andThen null
        every { queueItem.getId() } returns dispatchId
        every { queueItem.getDefinition() } returns Json.encodeToJsonElement(request)
        coEvery { queue.checkin(queueItem, any()) } returns true
        coEvery { queue.markComplete(queueItem) } returns Unit
        coEvery { queue.releaseJobLock(queueItem) } returns Unit
        coEvery { lockFactory.create(KubernetesJobQueueNames.dispatch) } returns lock
        coEvery { lock.tryAcquire(any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true

        KubernetesJobController(
            pool,
            clusters,
            executions,
            queueFactory,
            lockFactory,
            Json,
        ).reconcileAll()

        assertTrue(createdJobs.isEmpty())
        coVerify(exactly = 1) { queue.markCancelled(dispatchId) }
        coVerify(exactly = 1) { queue.markComplete(queueItem) }
        coVerify(exactly = 1) { executions.markCancelled(dispatchId) }
    }

    @Test
    fun `redelivery adopts the deterministic job and honors a concurrent cancellation`() = runTest {
        val clusterId = UUID.random()
        val dispatchId = UUID.random()
        val request = KubernetesJobRequest("android", "ci-existing")
        val jobName = kubernetesJobName(request.profile, request.idempotencyKey)
        val profileResource = resource()
        val existingJob = JobBuilder()
            .withMetadata(
                ObjectMetaBuilder()
                    .withName(jobName)
                    .withNamespace("workers")
                    .addToAnnotations(DISPATCH_ID_ANNOTATION, dispatchId.toString())
                    .addToAnnotations(IDEMPOTENCY_KEY_ANNOTATION, request.idempotencyKey)
                    .build()
            )
            .build()
        val queued = KubernetesJobExecution(
            dispatchId = dispatchId,
            profile = request.profile,
            idempotencyKey = request.idempotencyKey,
        )
        val cancelling = queued.copy(
            status = KubernetesJobExecutionStatus.CANCEL_REQUESTED,
            clusterId = clusterId,
            namespace = "workers",
            jobName = jobName,
            materializedAt = bosca.serialization.OffsetDateTime.now(),
        )
        val queueItem = mockk<Job>()
        val client = mockk<KubernetesClient>(relaxed = true)
        val pool = mockk<ClusterClientPool>()
        val clusters = mockk<ClusterRepository>()
        val executions = mockk<KubernetesJobExecutionRepository>(relaxed = true)
        val queueFactory = mockk<JobQueueFactory>()
        val queue = mockk<JobQueue>(relaxed = true)
        val lockFactory = mockk<DistributedLockFactory>()
        val lock = mockk<DistributedLock>()
        every { queueFactory.create(KubernetesJobQueueNames.queue) } returns queue
        coEvery { clusters.list() } returns listOf(testCluster(clusterId))
        coEvery { pool.get(clusterId) } returns client
        every {
            client.genericKubernetesResources(any<ResourceDefinitionContext>())
                .inAnyNamespace()
                .list()
        } returns GenericKubernetesResourceList().also { it.items = listOf(profileResource) }
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .withLabel(PROFILE_LABEL, "android")
                .withLabel(PROFILE_UID_LABEL, "profile-uid")
                .list()
        } returns JobListBuilder().build()
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .get()
        } returns existingJob
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                .delete()
        } returns emptyList()
        every { client.resource(profileResource).patchStatus() } returns profileResource
        every { queueItem.getId() } returns dispatchId
        every { queueItem.getDefinition() } returns Json.encodeToJsonElement(request)
        coEvery { queue.dequeue(any()) } returns queueItem andThen null
        coEvery { queue.checkin(queueItem, any()) } returns true
        coEvery { executions.getById(dispatchId) } returns queued
        coEvery {
            executions.markMaterialized(dispatchId, clusterId, "workers", jobName)
        } returns cancelling
        coEvery { executions.markCancelled(dispatchId) } returns cancelling.copy(
            status = KubernetesJobExecutionStatus.CANCELLED,
            finishedAt = bosca.serialization.OffsetDateTime.now(),
        )
        coEvery { lockFactory.create(KubernetesJobQueueNames.dispatch) } returns lock
        coEvery { lock.tryAcquire(any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true

        KubernetesJobController(
            pool,
            clusters,
            executions,
            queueFactory,
            lockFactory,
            Json,
        ).reconcileAll()

        coVerify(exactly = 1) { executions.markCancelled(dispatchId) }
        coVerify(exactly = 1) { queue.markComplete(queueItem) }
    }

    @Test
    fun `queued cancellation is settled before a new kubernetes job is created`() = runTest {
        val fixture = queuedDispatchFixture()
        coEvery { fixture.executions.getById(fixture.dispatchId) } returns fixture.execution.copy(
            status = KubernetesJobExecutionStatus.CANCEL_REQUESTED,
        )

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) { fixture.executions.markCancelled(fixture.dispatchId) }
        coVerify(exactly = 1) { fixture.queue.markComplete(fixture.queueItem) }
        coVerify(exactly = 0) { fixture.executions.markMaterialized(any(), any(), any(), any()) }
    }

    @Test
    fun `queued request conflicting with its execution identity remains retryable`() = runTest {
        val fixture = queuedDispatchFixture()
        coEvery { fixture.executions.getById(fixture.dispatchId) } returns fixture.execution.copy(
            idempotencyKey = "different",
        )

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) {
            fixture.queue.markFailed(
                fixture.queueItem,
                match { it.message.orEmpty().contains("conflicts with its queued request") },
                true,
            )
        }
        coVerify(exactly = 0) { fixture.queue.markComplete(fixture.queueItem) }
    }

    @Test
    fun `concurrent cancellation after create deletes the new kubernetes job`() = runTest {
        val fixture = queuedDispatchFixture()
        val jobName = kubernetesJobName(
            fixture.request.profile,
            fixture.request.idempotencyKey,
        )
        val created = slot<io.fabric8.kubernetes.api.model.batch.v1.Job>()
        every {
            fixture.client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .get()
        } returns null
        every {
            fixture.client.batch().v1().jobs()
                .inNamespace("workers")
                .resource(capture(created))
                .create()
        } answers { created.captured }
        coEvery {
            fixture.executions.markMaterialized(
                fixture.dispatchId,
                fixture.clusterId,
                "workers",
                jobName,
            )
        } returns fixture.execution.copy(
            status = KubernetesJobExecutionStatus.CANCEL_REQUESTED,
            clusterId = fixture.clusterId,
            namespace = "workers",
            jobName = jobName,
            materializedAt = bosca.serialization.OffsetDateTime.now(),
        )
        every {
            fixture.client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                .delete()
        } returns emptyList()

        fixture.controller.reconcileAll()

        verify(atLeast = 1) {
            fixture.client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                .delete()
        }
        coVerify(exactly = 1) { fixture.executions.markCancelled(fixture.dispatchId) }
        coVerify(exactly = 1) { fixture.queue.markComplete(fixture.queueItem) }
    }

    @Test
    fun `kubernetes create response without a name is retried`() = runTest {
        val fixture = queuedDispatchFixture()
        val jobName = kubernetesJobName(
            fixture.request.profile,
            fixture.request.idempotencyKey,
        )
        every {
            fixture.client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .get()
        } returns null
        every {
            fixture.client.batch().v1().jobs()
                .inNamespace("workers")
                .resource(any<io.fabric8.kubernetes.api.model.batch.v1.Job>())
                .create()
        } returns JobBuilder()
            .withMetadata(ObjectMetaBuilder().withNamespace("workers").build())
            .build()

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) {
            fixture.queue.markFailed(
                fixture.queueItem,
                match { it.message.orEmpty().contains("without metadata.name") },
                true,
            )
        }
        coVerify(exactly = 0) { fixture.queue.markComplete(fixture.queueItem) }
    }

    @Test
    fun `lost queue ownership after create leaves acknowledgement to the new owner`() = runTest {
        val fixture = queuedDispatchFixture()
        val jobName = kubernetesJobName(
            fixture.request.profile,
            fixture.request.idempotencyKey,
        )
        val created = slot<io.fabric8.kubernetes.api.model.batch.v1.Job>()
        every {
            fixture.client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .get()
        } returns null
        every {
            fixture.client.batch().v1().jobs()
                .inNamespace("workers")
                .resource(capture(created))
                .create()
        } answers { created.captured }
        coEvery {
            fixture.executions.markMaterialized(
                fixture.dispatchId,
                fixture.clusterId,
                "workers",
                jobName,
            )
        } returns fixture.execution.copy(
            status = KubernetesJobExecutionStatus.MATERIALIZED,
            clusterId = fixture.clusterId,
            namespace = "workers",
            jobName = jobName,
            materializedAt = bosca.serialization.OffsetDateTime.now(),
        )
        coEvery { fixture.queue.checkin(fixture.queueItem, any()) } returns true andThen false

        fixture.controller.reconcileAll()

        coVerify(exactly = 0) { fixture.queue.markComplete(fixture.queueItem) }
        coVerify(exactly = 0) { fixture.queue.markFailed(any(), any(), any()) }
        coVerify(exactly = 1) { fixture.queue.releaseJobLock(fixture.queueItem) }
    }

    @Test
    fun `concurrent Kubernetes create conflict adopts the winner without duplicate work`() = runTest {
        val fixture = queuedDispatchFixture()
        val jobName = kubernetesJobName(
            fixture.request.profile,
            fixture.request.idempotencyKey,
        )
        val existing = JobBuilder()
            .withMetadata(
                ObjectMetaBuilder()
                    .withName(jobName)
                    .withNamespace("workers")
                    .addToAnnotations(DISPATCH_ID_ANNOTATION, fixture.dispatchId.toString())
                    .addToAnnotations(
                        IDEMPOTENCY_KEY_ANNOTATION,
                        fixture.request.idempotencyKey,
                    )
                    .build()
            )
            .build()
        every {
            fixture.client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .get()
        } returns null andThen existing
        every {
            fixture.client.batch().v1().jobs()
                .inNamespace("workers")
                .resource(any<io.fabric8.kubernetes.api.model.batch.v1.Job>())
                .create()
        } throws KubernetesClientException("already exists", 409, null)
        coEvery {
            fixture.executions.markMaterialized(
                fixture.dispatchId,
                fixture.clusterId,
                "workers",
                jobName,
            )
        } returns fixture.execution.copy(
            status = KubernetesJobExecutionStatus.MATERIALIZED,
            clusterId = fixture.clusterId,
            namespace = "workers",
            jobName = jobName,
            materializedAt = bosca.serialization.OffsetDateTime.now(),
        )

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) { fixture.queue.markComplete(fixture.queueItem) }
        coVerify(exactly = 0) { fixture.queue.markFailed(any(), any(), any()) }
    }

    @Test
    fun `conflicting deterministic Kubernetes identity is retried without acknowledgement`() = runTest {
        val fixture = queuedDispatchFixture()
        val jobName = kubernetesJobName(
            fixture.request.profile,
            fixture.request.idempotencyKey,
        )
        every {
            fixture.client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .get()
        } returns JobBuilder()
            .withMetadata(
                ObjectMetaBuilder()
                    .withName(jobName)
                    .withNamespace("workers")
                    .addToAnnotations(DISPATCH_ID_ANNOTATION, UUID.random().toString())
                    .addToAnnotations(
                        IDEMPOTENCY_KEY_ANNOTATION,
                        fixture.request.idempotencyKey,
                    )
                    .build()
            )
            .build()

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) {
            fixture.queue.markFailed(
                fixture.queueItem,
                match { it.message.orEmpty().contains("conflicting dispatch identity") },
                true,
            )
        }
        coVerify(exactly = 0) { fixture.queue.markComplete(fixture.queueItem) }
    }

    @Test
    fun `non-conflict Kubernetes create failure remains retryable`() = runTest {
        val fixture = queuedDispatchFixture()
        val jobName = kubernetesJobName(
            fixture.request.profile,
            fixture.request.idempotencyKey,
        )
        every {
            fixture.client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .get()
        } returns null
        every {
            fixture.client.batch().v1().jobs()
                .inNamespace("workers")
                .resource(any<io.fabric8.kubernetes.api.model.batch.v1.Job>())
                .create()
        } throws KubernetesClientException("API unavailable", 503, null)

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) {
            fixture.queue.markFailed(fixture.queueItem, any(), true)
        }
        coVerify(exactly = 0) { fixture.queue.markComplete(fixture.queueItem) }
    }

    @Test
    fun `pod startup failure is persisted and the kubernetes job is deleted`() = runTest {
        val clusterId = UUID.random()
        val dispatchId = UUID.random()
        val profileResource = resource(startupFailureGraceSeconds = 0)
        val jobName = "android-image-pull-failure"
        val existing = JobBuilder()
            .withMetadata(
                ObjectMetaBuilder()
                    .withName(jobName)
                    .withNamespace("workers")
                    .addToAnnotations(DISPATCH_ID_ANNOTATION, dispatchId.toString())
                    .addToAnnotations(IDEMPOTENCY_KEY_ANNOTATION, "ci-2")
                    .build()
            )
            .build()
        val queued = KubernetesJobExecution(
            dispatchId = dispatchId,
            profile = "android",
            idempotencyKey = "ci-2",
        )
        val materialized = queued.copy(
            status = KubernetesJobExecutionStatus.MATERIALIZED,
            clusterId = clusterId,
            namespace = "workers",
            jobName = jobName,
            materializedAt = bosca.serialization.OffsetDateTime.now().minusSeconds(1),
        )
        val failedPod = PodBuilder()
            .withMetadata(
                ObjectMetaBuilder()
                    .withName("android-agent-abc")
                    .withNamespace("workers")
                    .build()
            )
            .withStatus(
                PodStatusBuilder()
                    .withPhase("Pending")
                    .withContainerStatuses(
                        ContainerStatusBuilder()
                            .withName("worker")
                            .withState(
                                ContainerStateBuilder()
                                    .withWaiting(
                                        ContainerStateWaitingBuilder()
                                            .withReason("ImagePullBackOff")
                                            .withMessage("manifest unknown")
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
        val client = mockk<KubernetesClient>(relaxed = true)
        val pool = mockk<ClusterClientPool>()
        val clusters = mockk<ClusterRepository>()
        val executions = mockk<KubernetesJobExecutionRepository>(relaxed = true)
        val queueFactory = mockk<JobQueueFactory>()
        val queue = mockk<JobQueue>()
        val lockFactory = mockk<DistributedLockFactory>()
        val lock = mockk<DistributedLock>()
        val failureMessage = slot<String>()

        every { queueFactory.create(KubernetesJobQueueNames.queue) } returns queue
        coEvery { clusters.list() } returns listOf(testCluster(clusterId))
        coEvery { pool.get(clusterId) } returns client
        every {
            client.genericKubernetesResources(any<ResourceDefinitionContext>())
                .inAnyNamespace()
                .list()
        } returns GenericKubernetesResourceList().also { it.items = listOf(profileResource) }
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .withLabel(PROFILE_LABEL, "android")
                .withLabel(PROFILE_UID_LABEL, "profile-uid")
                .list()
        } returns JobListBuilder().withItems(existing).build()
        every {
            client.pods()
                .inNamespace("workers")
                .withLabel("job-name", jobName)
                .list()
        } returns PodListBuilder().withItems(failedPod).build()
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                .delete()
        } returns emptyList()
        every { client.resource(profileResource).patchStatus() } returns profileResource
        coEvery { executions.findCancellationRequested(any()) } returns emptyList()
        coEvery { executions.getById(dispatchId) } returns queued
        coEvery {
            executions.markMaterialized(dispatchId, clusterId, "workers", jobName)
        } returns materialized
        coEvery {
            executions.markFailed(dispatchId, capture(failureMessage))
        } returns materialized.copy(
            status = KubernetesJobExecutionStatus.FAILED,
            message = "startup failed",
        )
        coEvery { queue.dequeue(any()) } returns null
        coEvery { lockFactory.create(KubernetesJobQueueNames.dispatch) } returns lock
        coEvery { lock.tryAcquire(any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true

        KubernetesJobController(
            pool,
            clusters,
            executions,
            queueFactory,
            lockFactory,
            Json,
        ).reconcileAll()

        assertTrue(failureMessage.captured.contains("ImagePullBackOff"))
        assertTrue(failureMessage.captured.contains("manifest unknown"))
        coVerify(exactly = 1) { executions.markFailed(dispatchId, any()) }
        verify(atLeast = 1) {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                .delete()
        }
        assertEquals(0, profileResource.additionalProperties.statusValue("activeJobs"))
    }

    @Test
    fun `unschedulable pod remains materialized after startup grace`() = runTest {
        val unschedulablePod = PodBuilder()
            .withMetadata(ObjectMetaBuilder().withName("linux-agent-pending").build())
            .withStatus(
                PodStatusBuilder()
                    .withPhase("Pending")
                    .withConditions(
                        PodConditionBuilder()
                            .withType("PodScheduled")
                            .withStatus("False")
                            .withReason("Unschedulable")
                            .withMessage("Insufficient memory")
                            .build()
                    )
                    .build()
            )
            .build()
        val fixture = observedExecutionFixture(
            status = KubernetesJobExecutionStatus.MATERIALIZED,
            pods = listOf(unschedulablePod),
            materializedAt = bosca.serialization.OffsetDateTime.now().minusSeconds(1),
            startupFailureGraceSeconds = 0,
        )

        fixture.controller.reconcileAll()

        coVerify(exactly = 0) { fixture.executions.markFailed(fixture.dispatchId, any()) }
        coVerify(exactly = 0) { fixture.executions.markSucceeded(fixture.dispatchId) }
        coVerify(exactly = 0) { fixture.executions.markRunning(fixture.dispatchId) }
        assertTrue(fixture.deletedJobNames.isEmpty())
    }

    @Test
    fun `completed Kubernetes job persists success`() = runTest {
        val condition = JobConditionBuilder()
            .withType("Complete")
            .withStatus("True")
            .build()
        val fixture = observedExecutionFixture(
            status = KubernetesJobExecutionStatus.MATERIALIZED,
            jobStatus = JobStatusBuilder().withConditions(condition).build(),
        )
        coEvery {
            fixture.executions.markSucceeded(fixture.dispatchId)
        } returns fixture.execution.copy(
            status = KubernetesJobExecutionStatus.SUCCEEDED,
            finishedAt = bosca.serialization.OffsetDateTime.now(),
        )

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) { fixture.executions.markSucceeded(fixture.dispatchId) }
        coVerify(exactly = 0) { fixture.executions.markFailed(fixture.dispatchId, any()) }
    }

    @Test
    fun `failed Kubernetes job persists pod termination detail instead of generic backoff`() = runTest {
        val condition = JobConditionBuilder()
            .withType("Failed")
            .withStatus("True")
            .withReason("BackoffLimitExceeded")
            .withMessage("Job has reached the specified backoff limit")
            .build()
        val failedPod = PodBuilder()
            .withMetadata(ObjectMetaBuilder().withName("linux-1").build())
            .withStatus(
                PodStatusBuilder()
                    .withContainerStatuses(
                        ContainerStatusBuilder()
                            .withName("worker")
                            .withState(
                                ContainerStateBuilder()
                                    .withTerminated(
                                        io.fabric8.kubernetes.api.model.ContainerStateTerminatedBuilder()
                                            .withExitCode(1)
                                            .withReason("Error")
                                            .withMessage("target job was cancelled")
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
        val fixture = observedExecutionFixture(
            status = KubernetesJobExecutionStatus.RUNNING,
            jobStatus = JobStatusBuilder().withConditions(condition).build(),
            pods = listOf(failedPod),
        )

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) {
            fixture.executions.markFailed(
                fixture.dispatchId,
                "Pod linux-1 container worker failed: Error (exit code 1) — target job was cancelled",
            )
        }
    }

    @Test
    fun `observed orphan job reconstructs its durable execution`() = runTest {
        val fixture = observedExecutionFixture(
            status = KubernetesJobExecutionStatus.MATERIALIZED,
            jobStatus = JobStatusBuilder().withActive(1).build(),
            executionExists = false,
        )
        coEvery {
            fixture.executions.create(
                fixture.dispatchId,
                "android",
                fixture.execution.idempotencyKey,
                any(),
            )
        } returns fixture.execution.copy(
            status = KubernetesJobExecutionStatus.QUEUED,
            clusterId = null,
            namespace = null,
            jobName = null,
            materializedAt = null,
        )
        coEvery {
            fixture.executions.markRunning(fixture.dispatchId)
        } returns fixture.execution.copy(status = KubernetesJobExecutionStatus.RUNNING)

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) {
            fixture.executions.create(
                fixture.dispatchId,
                "android",
                fixture.execution.idempotencyKey,
                any(),
            )
        }
    }

    @Test
    fun `terminal result releases the Kubernetes persistence finalizer`() = runTest {
        val edited = slot<io.fabric8.kubernetes.api.model.batch.v1.Job>()
        val fixture = observedExecutionFixture(
            status = KubernetesJobExecutionStatus.MATERIALIZED,
            jobStatus = JobStatusBuilder().withConditions(
                JobConditionBuilder().withType("Complete").withStatus("True").build()
            ).build(),
            finalizers = listOf("example.io/keep", JOB_RESULT_FINALIZER),
        )
        every {
            fixture.client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(requireNotNull(fixture.job.metadata.name))
                .edit(any<java.util.function.UnaryOperator<io.fabric8.kubernetes.api.model.batch.v1.Job>>())
        } answers {
            firstArg<java.util.function.UnaryOperator<io.fabric8.kubernetes.api.model.batch.v1.Job>>()
                .apply(fixture.job)
                .also { edited.captured = it }
        }
        coEvery {
            fixture.executions.markSucceeded(fixture.dispatchId)
        } returns fixture.execution.copy(
            status = KubernetesJobExecutionStatus.SUCCEEDED,
            finishedAt = bosca.serialization.OffsetDateTime.now(),
        )

        fixture.controller.reconcileAll()

        assertEquals(listOf("example.io/keep"), edited.captured.metadata.finalizers)
    }

    @Test
    fun `finalizer release ignores deletion races but propagates other API failures`() = runTest {
        suspend fun runWith(code: Int) {
            val fixture = observedExecutionFixture(
                status = KubernetesJobExecutionStatus.MATERIALIZED,
                jobStatus = JobStatusBuilder().withConditions(
                    JobConditionBuilder().withType("Complete").withStatus("True").build()
                ).build(),
                finalizers = listOf(JOB_RESULT_FINALIZER),
            )
            every {
                fixture.client.batch().v1().jobs()
                    .inNamespace("workers")
                    .withName(requireNotNull(fixture.job.metadata.name))
                    .edit(any<java.util.function.UnaryOperator<io.fabric8.kubernetes.api.model.batch.v1.Job>>())
            } throws KubernetesClientException("edit failed", code, null)
            coEvery {
                fixture.executions.markSucceeded(fixture.dispatchId)
            } returns fixture.execution.copy(
                status = KubernetesJobExecutionStatus.SUCCEEDED,
                finishedAt = bosca.serialization.OffsetDateTime.now(),
            )
            fixture.controller.reconcileAll()
        }

        runWith(404)
        assertFailsWith<KubernetesClientException> { runWith(503) }
    }

    @Test
    fun `failed Kubernetes job persists its condition message`() = runTest {
        val condition = JobConditionBuilder()
            .withType("Failed")
            .withStatus("True")
            .withMessage("worker exited 17")
            .build()
        val fixture = observedExecutionFixture(
            status = KubernetesJobExecutionStatus.RUNNING,
            jobStatus = JobStatusBuilder().withConditions(condition).build(),
        )
        coEvery {
            fixture.executions.markFailed(fixture.dispatchId, "worker exited 17")
        } returns fixture.execution.copy(
            status = KubernetesJobExecutionStatus.FAILED,
            message = "worker exited 17",
            finishedAt = bosca.serialization.OffsetDateTime.now(),
        )

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) {
            fixture.executions.markFailed(fixture.dispatchId, "worker exited 17")
        }
        coVerify(exactly = 0) { fixture.executions.markSucceeded(fixture.dispatchId) }
    }

    @Test
    fun `active Kubernetes job advances durable execution to running`() = runTest {
        val fixture = observedExecutionFixture(
            status = KubernetesJobExecutionStatus.MATERIALIZED,
            jobStatus = JobStatusBuilder().withActive(1).build(),
        )
        coEvery {
            fixture.executions.markRunning(fixture.dispatchId)
        } returns fixture.execution.copy(
            status = KubernetesJobExecutionStatus.RUNNING,
            startedAt = bosca.serialization.OffsetDateTime.now(),
        )

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) { fixture.executions.markRunning(fixture.dispatchId) }
    }

    @Test
    fun `succeeded pod phase advances a job before active count is reported`() = runTest {
        val fixture = observedExecutionFixture(
            status = KubernetesJobExecutionStatus.MATERIALIZED,
            pods = listOf(
                PodBuilder()
                    .withStatus(PodStatusBuilder().withPhase("Succeeded").build())
                    .build()
            ),
        )
        coEvery {
            fixture.executions.markRunning(fixture.dispatchId)
        } returns fixture.execution.copy(status = KubernetesJobExecutionStatus.RUNNING)

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) { fixture.executions.markRunning(fixture.dispatchId) }
    }

    @Test
    fun `startup failure waits for its configured grace period`() = runTest {
        val failedPod = PodBuilder()
            .withMetadata(ObjectMetaBuilder().withName("waiting").build())
            .withStatus(
                PodStatusBuilder().withContainerStatuses(
                    ContainerStatusBuilder()
                        .withName("worker")
                        .withState(
                            ContainerStateBuilder().withWaiting(
                                ContainerStateWaitingBuilder()
                                    .withReason("ImagePullBackOff")
                                    .build()
                            ).build()
                        )
                        .build()
                ).build()
            )
            .build()
        val fixture = observedExecutionFixture(
            status = KubernetesJobExecutionStatus.MATERIALIZED,
            pods = listOf(failedPod),
            materializedAt = bosca.serialization.OffsetDateTime.now(),
        )

        fixture.controller.reconcileAll()

        coVerify(exactly = 0) { fixture.executions.markFailed(fixture.dispatchId, any()) }
    }

    @Test
    fun `materialized execution requires its durable materialization timestamp`() = runTest {
        val fixture = observedExecutionFixture(
            status = KubernetesJobExecutionStatus.MATERIALIZED,
            materializedAt = null,
        )

        assertFailsWith<IllegalArgumentException> {
            fixture.controller.reconcileAll()
        }
    }

    @Test
    fun `deleting active Kubernetes job fails its durable execution`() = runTest {
        val fixture = observedExecutionFixture(
            status = KubernetesJobExecutionStatus.MATERIALIZED,
            deletionTimestamp = bosca.serialization.OffsetDateTime.now().toString(),
        )
        coEvery {
            fixture.executions.markFailed(
                fixture.dispatchId,
                "Kubernetes Job was deleted before reaching a terminal state",
            )
        } returns fixture.execution.copy(
            status = KubernetesJobExecutionStatus.FAILED,
            finishedAt = bosca.serialization.OffsetDateTime.now(),
        )

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) {
            fixture.executions.markFailed(
                fixture.dispatchId,
                "Kubernetes Job was deleted before reaching a terminal state",
            )
        }
    }

    @Test
    fun `missing materialized jobs settle every externally observed terminal outcome`() = runTest {
        val fixture = emptyProfileFixture()
        val clusterId = UUID.random()
        val client = mockk<KubernetesClient>(relaxed = true)
        val missing = materializedExecution(clusterId, "missing")
        val failed = materializedExecution(clusterId, "failed")
        val complete = materializedExecution(clusterId, "complete")
        val deleting = materializedExecution(clusterId, "deleting")
        val active = materializedExecution(clusterId, "active")
        val jobs = mapOf(
            "failed" to JobBuilder()
                .withMetadata(jobMetadata("failed"))
                .withStatus(
                    JobStatusBuilder().withConditions(
                        JobConditionBuilder()
                            .withType("Failed")
                            .withStatus("True")
                            .withReason("DeadlineExceeded")
                            .build()
                    ).build()
                )
                .build(),
            "complete" to JobBuilder()
                .withMetadata(jobMetadata("complete"))
                .withStatus(
                    JobStatusBuilder().withConditions(
                        JobConditionBuilder()
                            .withType("Complete")
                            .withStatus("True")
                            .build()
                    ).build()
                )
                .build(),
            "deleting" to JobBuilder()
                .withMetadata(jobMetadata("deleting").also {
                    it.deletionTimestamp = bosca.serialization.OffsetDateTime.now().toString()
                })
                .build(),
            "active" to JobBuilder().withMetadata(jobMetadata("active")).build(),
        )
        coEvery { fixture.executions.findMaterializedOrRunning(any()) } returns
            listOf(missing, failed, complete, deleting, active)
        coEvery { fixture.pool.get(clusterId) } returns client
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withName("missing")
                .get()
        } returns null
        jobs.forEach { (name, job) ->
            every {
                client.batch().v1().jobs()
                    .inNamespace("workers")
                    .withName(name)
                    .get()
            } returns job
        }

        fixture.controller.reconcileAll()

        coVerify(exactly = 1) {
            fixture.executions.markFailed(
                missing.dispatchId,
                "Kubernetes Job workers/missing disappeared before its result was persisted",
            )
        }
        coVerify(exactly = 1) {
            fixture.executions.markFailed(failed.dispatchId, "DeadlineExceeded")
        }
        coVerify(exactly = 1) { fixture.executions.markSucceeded(complete.dispatchId) }
        coVerify(exactly = 1) {
            fixture.executions.markFailed(
                deleting.dispatchId,
                "Kubernetes Job workers/deleting was deleted before completion",
            )
        }
        coVerify(exactly = 0) {
            fixture.executions.markFailed(active.dispatchId, any())
        }
    }

    @Test
    fun `missing execution recovery skips incomplete identities and transient cluster failures`() = runTest {
        val fixture = emptyProfileFixture()
        val clusterId = UUID.random()
        val unavailableClusterId = UUID.random()
        val lookupFailureClusterId = UUID.random()
        val lookupClient = mockk<KubernetesClient>(relaxed = true)
        val incomplete = listOf(
            KubernetesJobExecution(
                dispatchId = UUID.random(),
                profile = "android",
                idempotencyKey = "no-cluster",
                status = KubernetesJobExecutionStatus.MATERIALIZED,
            ),
            KubernetesJobExecution(
                dispatchId = UUID.random(),
                profile = "android",
                idempotencyKey = "no-namespace",
                status = KubernetesJobExecutionStatus.MATERIALIZED,
                clusterId = clusterId,
            ),
            KubernetesJobExecution(
                dispatchId = UUID.random(),
                profile = "android",
                idempotencyKey = "no-name",
                status = KubernetesJobExecutionStatus.MATERIALIZED,
                clusterId = clusterId,
                namespace = "workers",
            ),
        )
        val unavailable = materializedExecution(unavailableClusterId, "unavailable")
        val lookupFailure = materializedExecution(lookupFailureClusterId, "lookup-failure")
        coEvery { fixture.executions.findMaterializedOrRunning(any()) } returns
            incomplete + unavailable + lookupFailure
        coEvery { fixture.pool.get(unavailableClusterId) } throws
            IllegalStateException("cluster temporarily unavailable")
        coEvery { fixture.pool.get(lookupFailureClusterId) } returns lookupClient
        every {
            lookupClient.batch().v1().jobs()
                .inNamespace("workers")
                .withName("lookup-failure")
                .get()
        } throws IllegalStateException("API temporarily unavailable")

        fixture.controller.reconcileAll()

        coVerify(exactly = 0) { fixture.executions.markFailed(any(), any()) }
        coVerify(exactly = 0) { fixture.executions.markSucceeded(any()) }
    }

    @Test
    fun `cluster and profile failures are isolated from healthy profiles`() = runTest {
        val unavailableId = UUID.random()
        val noCrdId = UUID.random()
        val brokenApiId = UUID.random()
        val healthyId = UUID.random()
        val noCrd = mockk<KubernetesClient>(relaxed = true)
        val brokenApi = mockk<KubernetesClient>(relaxed = true)
        val healthy = mockk<KubernetesClient>(relaxed = true)
        val statusResource = mockk<NamespaceableResource<GenericKubernetesResource>>()
        val profileResource = resource()
        val invalidResource = GenericKubernetesResource()
        val pool = mockk<ClusterClientPool>()
        val clusters = mockk<ClusterRepository>()
        val executions = mockk<KubernetesJobExecutionRepository>(relaxed = true)
        val queueFactory = mockk<JobQueueFactory>()
        val queue = mockk<JobQueue>(relaxed = true)
        val lockFactory = mockk<DistributedLockFactory>()
        val lock = mockk<DistributedLock>()
        every { queueFactory.create(KubernetesJobQueueNames.queue) } returns queue
        coEvery { clusters.list() } returns listOf(
            testCluster(unavailableId),
            testCluster(noCrdId),
            testCluster(brokenApiId),
            testCluster(healthyId),
        )
        coEvery { pool.get(unavailableId) } throws IllegalStateException("credentials unavailable")
        coEvery { pool.get(noCrdId) } returns noCrd
        coEvery { pool.get(brokenApiId) } returns brokenApi
        coEvery { pool.get(healthyId) } returns healthy
        every {
            noCrd.genericKubernetesResources(any<ResourceDefinitionContext>())
                .inAnyNamespace()
                .list()
        } throws KubernetesClientException("CRD not installed", 404, null)
        every {
            brokenApi.genericKubernetesResources(any<ResourceDefinitionContext>())
                .inAnyNamespace()
                .list()
        } throws KubernetesClientException("API unavailable", 503, null)
        every {
            healthy.genericKubernetesResources(any<ResourceDefinitionContext>())
                .inAnyNamespace()
                .list()
        } returns GenericKubernetesResourceList().also {
            it.items = listOf(invalidResource, profileResource)
        }
        every {
            healthy.batch().v1().jobs()
                .inNamespace("workers")
                .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .withLabel(PROFILE_LABEL, "android")
                .withLabel(PROFILE_UID_LABEL, "profile-uid")
                .list()
        } returns JobListBuilder().build()
        every { healthy.resource(profileResource) } returns statusResource
        every { statusResource.patchStatus() } throws IllegalStateException("status update denied")
        coEvery { queue.dequeue(any()) } returns null
        coEvery { lockFactory.create(KubernetesJobQueueNames.dispatch) } returns lock
        coEvery { lock.tryAcquire(any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true

        KubernetesJobController(
            pool,
            clusters,
            executions,
            queueFactory,
            lockFactory,
            Json,
        ).reconcileAll()

        verify(exactly = 1) { statusResource.patchStatus() }
        coVerify(exactly = 1) { lock.release() }
    }

    @Test
    fun `observed terminal database states prevent workloads from continuing`() = runTest {
        val cancelRequested = observedExecutionFixture(KubernetesJobExecutionStatus.CANCEL_REQUESTED)
        coEvery {
            cancelRequested.executions.markCancelled(cancelRequested.dispatchId)
        } returns cancelRequested.execution.copy(
            status = KubernetesJobExecutionStatus.CANCELLED,
            finishedAt = bosca.serialization.OffsetDateTime.now(),
        )
        cancelRequested.controller.reconcileAll()
        coVerify(exactly = 1) {
            cancelRequested.executions.markCancelled(cancelRequested.dispatchId)
        }

        val cancelled = observedExecutionFixture(KubernetesJobExecutionStatus.CANCELLED)
        cancelled.controller.reconcileAll()
        coVerify(exactly = 0) { cancelled.executions.markCancelled(cancelled.dispatchId) }

        val failed = observedExecutionFixture(KubernetesJobExecutionStatus.FAILED)
        failed.controller.reconcileAll()

        val succeeded = observedExecutionFixture(KubernetesJobExecutionStatus.SUCCEEDED)
        succeeded.controller.reconcileAll()
        coVerify(exactly = 0) { succeeded.executions.markFailed(succeeded.dispatchId, any()) }
    }

    private fun testCluster(clusterId: UUID) = Cluster(
        id = clusterId,
        name = "test",
        provider = "kind",
        region = "local",
        environment = ClusterEnvironment.DEVELOPMENT,
    )

    private fun resource(
        name: String = "android",
        uid: String = "profile-uid",
        maxParallelism: Int = 2,
        startupFailureGraceSeconds: Long? = null,
    ): GenericKubernetesResource {
        val podTemplate = PodTemplateSpecBuilder()
            .withSpec(
                PodSpecBuilder()
                    .withContainers(
                        ContainerBuilder()
                            .withName("worker")
                            .withImage("example/android:latest")
                            .build()
                    )
                    .build()
            )
            .build()
        @Suppress("UNCHECKED_CAST")
        val podTemplateMap = Serialization.jsonMapper().convertValue(
            podTemplate,
            Map::class.java,
        ) as Map<String, Any?>
        return GenericKubernetesResource().also {
            it.apiVersion = API_VERSION
            it.kind = KIND
            it.metadata = ObjectMetaBuilder()
                .withName(name)
                .withNamespace("workers")
                .withUid(uid)
                .withGeneration(1)
                .build()
            it.additionalProperties["spec"] = mapOf(
                "maxParallelism" to maxParallelism,
                "job" to buildMap {
                    startupFailureGraceSeconds?.let {
                        put("startupFailureGraceSeconds", it)
                    }
                },
                "podTemplate" to podTemplateMap,
            )
        }
    }

    private fun emptyProfileFixture(intervalMillis: Long = 5_000): EmptyProfileFixture {
        val pool = mockk<ClusterClientPool>()
        val clusters = mockk<ClusterRepository>()
        val executions = mockk<KubernetesJobExecutionRepository>(relaxed = true)
        val queueFactory = mockk<JobQueueFactory>()
        val queue = mockk<JobQueue>(relaxed = true)
        val lockFactory = mockk<DistributedLockFactory>()
        val lock = mockk<DistributedLock>()
        every { queueFactory.create(KubernetesJobQueueNames.queue) } returns queue
        coEvery { clusters.list() } returns emptyList()
        coEvery { executions.claimQueuedForPublication(any(), any()) } returns emptyList()
        coEvery { executions.findCancellationRequested(any()) } returns emptyList()
        coEvery { executions.findMaterializedOrRunning(any()) } returns emptyList()
        coEvery { queue.dequeue(any()) } returns null
        coEvery { lockFactory.create(KubernetesJobQueueNames.dispatch) } returns lock
        coEvery { lock.tryAcquire(any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true
        return EmptyProfileFixture(
            controller = KubernetesJobController(
                pool,
                clusters,
                executions,
                queueFactory,
                lockFactory,
                Json,
                intervalMillis,
            ),
            executions = executions,
            queue = queue,
            pool = pool,
            lock = lock,
            clusters = clusters,
        )
    }

    private fun observedExecutionFixture(
        status: KubernetesJobExecutionStatus,
        jobStatus: io.fabric8.kubernetes.api.model.batch.v1.JobStatus? = null,
        deletionTimestamp: String? = null,
        executionExists: Boolean = true,
        finalizers: List<String> = emptyList(),
        pods: List<io.fabric8.kubernetes.api.model.Pod> = emptyList(),
        materializedAt: bosca.serialization.OffsetDateTime? =
            bosca.serialization.OffsetDateTime.now().minusSeconds(1),
        startupFailureGraceSeconds: Long? = null,
    ): ObservedExecutionFixture {
        val clusterId = UUID.random()
        val dispatchId = UUID.random()
        val jobName = "android-${dispatchId.toString().take(8)}"
        val profileResource = resource(startupFailureGraceSeconds = startupFailureGraceSeconds)
        val job = JobBuilder()
            .withMetadata(
                ObjectMetaBuilder()
                    .withName(jobName)
                    .withNamespace("workers")
                    .withDeletionTimestamp(deletionTimestamp)
                    .withFinalizers(finalizers)
                    .addToAnnotations(DISPATCH_ID_ANNOTATION, dispatchId.toString())
                    .addToAnnotations(IDEMPOTENCY_KEY_ANNOTATION, "ci-1")
                    .build()
            )
            .withStatus(jobStatus)
            .build()
        val execution = KubernetesJobExecution(
            dispatchId = dispatchId,
            profile = "android",
            idempotencyKey = "ci-1",
            status = status,
            clusterId = clusterId,
            namespace = "workers",
            jobName = jobName,
            materializedAt = materializedAt,
        )
        val client = mockk<KubernetesClient>(relaxed = true)
        val pool = mockk<ClusterClientPool>()
        val clusters = mockk<ClusterRepository>()
        val executions = mockk<KubernetesJobExecutionRepository>(relaxed = true)
        val queueFactory = mockk<JobQueueFactory>()
        val queue = mockk<JobQueue>(relaxed = true)
        val lockFactory = mockk<DistributedLockFactory>()
        val lock = mockk<DistributedLock>()
        val statusResource = mockk<NamespaceableResource<GenericKubernetesResource>>()
        val deletedJobNames = mutableListOf<String>()
        every { queueFactory.create(KubernetesJobQueueNames.queue) } returns queue
        coEvery { clusters.list() } returns listOf(testCluster(clusterId))
        coEvery { pool.get(clusterId) } returns client
        every {
            client.genericKubernetesResources(any<ResourceDefinitionContext>())
                .inAnyNamespace()
                .list()
        } returns GenericKubernetesResourceList().also { it.items = listOf(profileResource) }
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .withLabel(PROFILE_LABEL, "android")
                .withLabel(PROFILE_UID_LABEL, "profile-uid")
                .list()
        } returns JobListBuilder().withItems(job).build()
        every {
            client.pods()
                .inNamespace("workers")
                .withLabel("job-name", jobName)
                .list()
        } returns PodListBuilder().withItems(pods).build()
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withName(jobName)
                .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                .delete()
        } answers {
            deletedJobNames += jobName
            emptyList()
        }
        every { client.resource(profileResource) } returns statusResource
        every { statusResource.patchStatus() } returns profileResource
        coEvery { executions.claimQueuedForPublication(any(), any()) } returns emptyList()
        coEvery { executions.findCancellationRequested(any()) } returns emptyList()
        coEvery { executions.findMaterializedOrRunning(any()) } returns emptyList()
        coEvery { executions.getById(dispatchId) } returns execution.takeIf { executionExists }
        coEvery {
            executions.markMaterialized(dispatchId, clusterId, "workers", jobName)
        } returns execution
        coEvery { queue.dequeue(any()) } returns null
        coEvery { lockFactory.create(KubernetesJobQueueNames.dispatch) } returns lock
        coEvery { lock.tryAcquire(any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true
        return ObservedExecutionFixture(
            controller = KubernetesJobController(
                pool,
                clusters,
                executions,
                queueFactory,
                lockFactory,
                Json,
            ),
            executions = executions,
            dispatchId = dispatchId,
            execution = execution,
            client = client,
            job = job,
            deletedJobNames = deletedJobNames,
        )
    }

    private fun materializedExecution(
        clusterId: UUID,
        jobName: String,
    ) = KubernetesJobExecution(
        dispatchId = UUID.random(),
        profile = "android",
        idempotencyKey = jobName,
        status = KubernetesJobExecutionStatus.MATERIALIZED,
        clusterId = clusterId,
        namespace = "workers",
        jobName = jobName,
        materializedAt = bosca.serialization.OffsetDateTime.now().minusSeconds(1),
    )

    private fun jobMetadata(name: String) = ObjectMetaBuilder()
        .withName(name)
        .withNamespace("workers")
        .build()

    private fun queuedDispatchFixture(): QueuedDispatchFixture {
        val clusterId = UUID.random()
        val dispatchId = UUID.random()
        val request = KubernetesJobRequest("android", "ci-create-race")
        val execution = KubernetesJobExecution(
            dispatchId = dispatchId,
            profile = request.profile,
            idempotencyKey = request.idempotencyKey,
            request = Json.encodeToJsonElement(request),
        )
        val profileResource = resource()
        val client = mockk<KubernetesClient>(relaxed = true)
        val pool = mockk<ClusterClientPool>()
        val clusters = mockk<ClusterRepository>()
        val executions = mockk<KubernetesJobExecutionRepository>(relaxed = true)
        val queueFactory = mockk<JobQueueFactory>()
        val queue = mockk<JobQueue>(relaxed = true)
        val queueItem = mockk<Job>()
        val lockFactory = mockk<DistributedLockFactory>()
        val lock = mockk<DistributedLock>()
        every { queueFactory.create(KubernetesJobQueueNames.queue) } returns queue
        coEvery { clusters.list() } returns listOf(testCluster(clusterId))
        coEvery { pool.get(clusterId) } returns client
        every {
            client.genericKubernetesResources(any<ResourceDefinitionContext>())
                .inAnyNamespace()
                .list()
        } returns GenericKubernetesResourceList().also { it.items = listOf(profileResource) }
        every {
            client.batch().v1().jobs()
                .inNamespace("workers")
                .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .withLabel(PROFILE_LABEL, "android")
                .withLabel(PROFILE_UID_LABEL, "profile-uid")
                .list()
        } returns JobListBuilder().build()
        every { client.resource(profileResource).patchStatus() } returns profileResource
        coEvery { executions.getById(dispatchId) } returns execution
        every { queueItem.getId() } returns dispatchId
        every { queueItem.getDefinition() } returns Json.encodeToJsonElement(request)
        every { queueItem.isFullyComplete() } returns false
        coEvery { queue.dequeue(any()) } returns queueItem andThen null
        coEvery { queue.checkin(queueItem, any()) } returns true
        coEvery { lockFactory.create(KubernetesJobQueueNames.dispatch) } returns lock
        coEvery { lock.tryAcquire(any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true
        return QueuedDispatchFixture(
            controller = KubernetesJobController(
                pool,
                clusters,
                executions,
                queueFactory,
                lockFactory,
                Json,
            ),
            client = client,
            executions = executions,
            queue = queue,
            queueItem = queueItem,
            request = request,
            execution = execution,
            dispatchId = dispatchId,
            clusterId = clusterId,
        )
    }
}

private data class EmptyProfileFixture(
    val controller: KubernetesJobController,
    val executions: KubernetesJobExecutionRepository,
    val queue: JobQueue,
    val pool: ClusterClientPool,
    val lock: DistributedLock,
    val clusters: ClusterRepository,
)

private data class ObservedExecutionFixture(
    val controller: KubernetesJobController,
    val executions: KubernetesJobExecutionRepository,
    val dispatchId: UUID,
    val execution: KubernetesJobExecution,
    val client: KubernetesClient,
    val job: io.fabric8.kubernetes.api.model.batch.v1.Job,
    val deletedJobNames: List<String>,
)

private data class QueuedDispatchFixture(
    val controller: KubernetesJobController,
    val client: KubernetesClient,
    val executions: KubernetesJobExecutionRepository,
    val queue: JobQueue,
    val queueItem: Job,
    val request: KubernetesJobRequest,
    val execution: KubernetesJobExecution,
    val dispatchId: UUID,
    val clusterId: UUID,
)

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.statusValue(name: String): Any? =
    (this["status"] as Map<String, Any?>)[name]
