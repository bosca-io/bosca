package bosca.content.transition.service

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionJobHistory
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.service.CollectionJobHistoryService
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataJobHistoryService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.state.model.State
import bosca.content.state.model.WorkflowStateType
import bosca.content.state.service.StateService
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.model.Transition
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.MissingProviderException
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.security.model.Group
import bosca.security.model.Principal
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Unit-level coverage for the instance (non-companion) logic of [Transitioner].
 *
 * The companion helpers (`getDelay`, `getEffectiveAdvertisedEpoch`, `epochAttribute`)
 * are exhaustively covered by [TransitionerTest], [TransitionerGetDelayTest],
 * [TransitionerEpochAttributeTest], and [TransitionerAdvertisedPublishEdgeCaseTest],
 * and the full happy-path job pipeline is covered by the container-backed
 * `CollectionTransitionEndToEndTest` / `AdvertisedPublishTransitionEndToEndTest`.
 *
 * This suite fills the remaining branches — the pre-condition guards, the
 * pending-state handling, the enqueue-phase validation errors, the immediate vs.
 * delayed enqueue paths, and the rollback-on-failure logic — using pure MockK
 * with no database or job queue, by handing [Transitioner.beginTransition] a
 * pre-loaded [item] so no repository lookup occurs.
 */
@OptIn(InternalDI::class)
class TransitionerCoverageTest {

    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val metadataJobHistory = mockk<MetadataJobHistoryService>(relaxed = true)
    private val collectionService = mockk<CollectionService>(relaxed = true)
    private val collectionJobHistory = mockk<CollectionJobHistoryService>(relaxed = true)
    private val transitionService = mockk<TransitionService>(relaxed = true)
    private val stateService = mockk<StateService>(relaxed = true)
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>(relaxed = true)

    private lateinit var transitioner: Transitioner

    private val principal = Principal(id = UUID.random())
    private val groups = listOf(Group(id = UUID.random(), name = "sa", description = "", type = bosca.security.model.GroupType.SYSTEM))
    private val auth = ImpersonatedAuthenticationContext(principal, groups)

    @BeforeTest
    fun setup() {
        unmockkAll()
        ProviderRegistry.clear()
        transitioner = Transitioner(
            metadataService,
            metadataJobHistory,
            collectionService,
            collectionJobHistory,
            transitionService,
            stateService,
            metadataPermissionEvaluator,
            collectionPermissionEvaluator,
        )
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    // ── Test fixtures ────────────────────────────────────────────────────────

    private fun metadata(
        stateId: String = "draft",
        pendingId: String? = null,
        ready: OffsetDateTime? = OffsetDateTime.now(),
        attributes: JsonObject? = null,
    ) = Metadata(
        id = UUID.random(),
        version = 1,
        name = "Doc",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = 0L,
        languageTag = "en",
        attributes = attributes,
        ready = ready,
        workflowStateId = stateId,
        workflowStatePendingId = pendingId,
    )

    private fun collection(
        stateId: String = "draft",
        pendingId: String? = null,
        ready: OffsetDateTime? = OffsetDateTime.now(),
        attributes: JsonObject? = null,
    ) = Collection(
        id = UUID.random(),
        name = "Coll",
        languageTag = "en",
        attributes = attributes,
        ready = ready,
        workflowStateId = stateId,
        workflowStatePendingId = pendingId,
    )

    private fun languageVariant(
        stateId: String = "draft",
        pendingId: String? = null,
        ready: OffsetDateTime? = OffsetDateTime.now(),
        languageTag: String = "es",
    ) = CollectionLanguageVariant(
        id = UUID.random(),
        languageTag = languageTag,
        name = "Variant",
        ready = ready,
        workflowStateId = stateId,
        workflowStatePendingId = pendingId,
    )

    private fun state(id: String, type: WorkflowStateType, jobName: String? = null, configuration: kotlinx.serialization.json.JsonElement = JsonObject(emptyMap())) =
        State(id = id, name = id, description = "", type = type, configuration = configuration, jobName = jobName)

    private fun transition(
        from: String = "draft",
        to: String = "published",
        enter: String? = null,
        exit: String? = null,
        configuration: kotlinx.serialization.json.JsonElement? = null,
    ) = Transition(
        fromStateId = from,
        toStateId = to,
        description = "",
        enterJobName = enter,
        exitJobName = exit,
        configuration = configuration,
    )

    /** Registers a relaxed enqueuer under [name] whose enqueue/enqueueLater return a job with [jobId]. */
    private fun registerEnqueuer(name: String, jobId: UUID = UUID.random()): JobConfigurationEnqueuer {
        val enqueuer = mockk<JobConfigurationEnqueuer>(relaxed = true)
        val job = mockk<Job>(relaxed = true)
        every { job.getId() } returns jobId
        coEvery { enqueuer.enqueue(any(), any()) } returns job
        coEvery { enqueuer.enqueueLater(any(), any(), any()) } returns job
        provides<JobConfigurationEnqueuer>(name = name, singleton = true) { enqueuer }
        return enqueuer
    }

    // ── beginTransition guards ───────────────────────────────────────────────

    @Test
    fun `beginTransition inside a transaction errors`() = runTest {
        val cm = mockk<ConnectionManager>(relaxed = true)
        every { cm.inTransaction } returns true
        val item = metadata()
        val request = BeginTransitionInput(metadataId = item.id, version = 1, stateId = "published", status = "s")

        val ex = assertFailsWith<IllegalStateException> {
            withContext(cm.asCoroutineContext()) {
                transitioner.beginTransition(auth, request, item)
            }
        }
        assertEquals("cannot call beginTransition inside a transaction", ex.message)
    }

    @Test
    fun `beginTransition with no principal errors`() = runTest {
        val noPrincipal = mockk<bosca.security.service.AuthenticationContext>()
        every { noPrincipal.principal() } returns null
        val item = metadata()
        val request = BeginTransitionInput(metadataId = item.id, version = 1, stateId = "published", status = "s")

        val ex = assertFailsWith<IllegalStateException> {
            transitioner.beginTransition(noPrincipal, request, item)
        }
        assertEquals("no principal", ex.message)
    }

    @Test
    fun `beginTransition already in requested state errors`() = runTest {
        val item = metadata(stateId = "draft")
        val request = BeginTransitionInput(metadataId = item.id, version = 1, stateId = "draft", status = "s")

        val ex = assertFailsWith<IllegalStateException> {
            transitioner.beginTransition(auth, request, item)
        }
        assertEquals("content is already in state 'draft'", ex.message)
    }

    @Test
    fun `beginTransition already in requested state is allowed when restart true`() = runTest {
        val item = metadata(stateId = "draft")
        val request = BeginTransitionInput(metadataId = item.id, version = 1, stateId = "draft", status = "s", restart = true)
        coEvery { transitionService.get("draft", "draft") } returns transition(from = "draft", to = "draft")
        coEvery { stateService.get("draft") } returns state("draft", WorkflowStateType.DRAFT, jobName = "job-draft")
        registerEnqueuer("job-draft")
        val pending = metadata(stateId = "draft", pendingId = "draft")
        coEvery { metadataService.setPendingState(any(), any(), any(), any(), any(), any()) } returns pending
        coEvery { metadataJobHistory.addHistory(any()) } answers { firstArg() }

        // restart=true bypasses the "already in state" guard; the transition then proceeds
        val result = transitioner.beginTransition(auth, request, item)
        assertSame(pending, result)
    }

    // ── existing pending-state handling ──────────────────────────────────────

    @Test
    fun `beginTransition on metadata with existing pending state fails the old pending transition`() = runTest {
        val item = metadata(stateId = "draft", pendingId = "published")
        val request = BeginTransitionInput(metadataId = item.id, version = 1, stateId = "published", status = "s")
        coEvery { transitionService.get("draft", "published") } returns transition()
        coEvery { stateService.get("draft") } returns state("draft", WorkflowStateType.DRAFT)
        coEvery { stateService.get("published") } returns state("published", WorkflowStateType.PUBLISHED, jobName = "job-pub")
        registerEnqueuer("job-pub")
        val pending = metadata(stateId = "draft", pendingId = "published")
        coEvery { metadataService.setPendingState(any(), any(), any(), any(), any(), any()) } returns pending

        transitioner.beginTransition(auth, request, item)

        coVerify { metadataService.setPendingStateFailed(item, "restarting a pending transition", principal = any()) }
    }

    @Test
    fun `beginTransition on collection with existing pending state errors`() = runTest {
        val item = collection(stateId = "draft", pendingId = "published")
        val request = BeginTransitionInput(collectionId = item.id, stateId = "published", status = "s")

        val ex = assertFailsWith<IllegalStateException> {
            transitioner.beginTransition(auth, request, item)
        }
        assertEquals("collection is already in a pending state", ex.message)
    }

    // ── transition resolution ────────────────────────────────────────────────

    @Test
    fun `beginTransition errors when no transition exists`() = runTest {
        val item = metadata(stateId = "draft")
        val request = BeginTransitionInput(metadataId = item.id, version = 1, stateId = "published", status = "s")
        coEvery { transitionService.get("draft", "published") } returns null

        val ex = assertFailsWith<IllegalStateException> {
            transitioner.beginTransition(auth, request, item)
        }
        assertEquals("transition doesn't exist: draft -> published", ex.message)
    }

    // ── enqueuePhase validation guards ───────────────────────────────────────

    @Test
    fun `beginTransition errors when pending manual processing not allowed`() = runTest {
        // item is in a PENDING-type state, allowProcessing=false, ready=null -> first guard fires
        val item = metadata(stateId = "pending", ready = null)
        val request = BeginTransitionInput(metadataId = item.id, version = 1, stateId = "draft", status = "s")
        coEvery { transitionService.get("pending", "draft") } returns transition(from = "pending", to = "draft")
        coEvery { stateService.get("pending") } returns state("pending", WorkflowStateType.PENDING)
        val pending = metadata(stateId = "pending", ready = null)
        coEvery { metadataService.setPendingState(any(), any(), any(), any(), any(), any()) } returns pending

        val ex = assertFailsWith<IllegalStateException> {
            transitioner.beginTransition(auth, request, item)
        }
        assertEquals(
            "manual transition to processing isn't allowed, please mark as ready instead and wait for the item to be transitioned to draft",
            ex.message,
        )
        // failure inside try -> rollback via setPendingStateFailed
        coVerify { metadataService.setPendingStateFailed(pending, any(), principal = any()) }
    }

    @Test
    fun `beginTransition errors when not ready and target is neither published nor advertised`() = runTest {
        // item ready=null, state type DRAFT (not published/advertised) -> second guard fires
        val item = metadata(stateId = "draft", ready = null)
        val request = BeginTransitionInput(metadataId = item.id, version = 1, stateId = "review", status = "s", allowProcessing = true)
        coEvery { transitionService.get("draft", "review") } returns transition(from = "draft", to = "review")
        coEvery { stateService.get("draft") } returns state("draft", WorkflowStateType.DRAFT)
        val pending = metadata(stateId = "draft", ready = null)
        coEvery { metadataService.setPendingState(any(), any(), any(), any(), any(), any()) } returns pending

        val ex = assertFailsWith<IllegalStateException> {
            transitioner.beginTransition(auth, request, item)
        }
        assertEquals("please mark as ready before transitioning to a new state", ex.message)
    }

    @Test
    fun `beginTransition allows not-ready item when its current state is advertised`() = runTest {
        // ready=null but the item's CURRENT state type is ADVERTISED -> second guard skipped
        // (the guard exempts items already in a PUBLISHED/ADVERTISED state, regardless of readiness).
        val item = metadata(stateId = "advertised", ready = null)
        val request = BeginTransitionInput(metadataId = item.id, version = 1, stateId = "published", status = "s")
        coEvery { transitionService.get("advertised", "published") } returns transition(from = "advertised", to = "published")
        coEvery { stateService.get("advertised") } returns state("advertised", WorkflowStateType.ADVERTISED)
        coEvery { stateService.get("published") } returns state("published", WorkflowStateType.PUBLISHED, jobName = "job-pub")
        registerEnqueuer("job-pub")
        val pending = metadata(stateId = "advertised", ready = null)
        coEvery { metadataService.setPendingState(any(), any(), any(), any(), any(), any()) } returns pending
        coEvery { metadataJobHistory.addHistory(any()) } answers { firstArg() }

        val result = transitioner.beginTransition(auth, request, item)
        assertSame(pending, result)
    }

    @Test
    fun `beginTransition errors when target state does not exist`() = runTest {
        val item = metadata(stateId = "draft")
        val request = BeginTransitionInput(metadataId = item.id, version = 1, stateId = "published", status = "s")
        coEvery { transitionService.get("draft", "published") } returns transition()
        // item state exists (so guards pass) but the lookup target state is missing
        coEvery { stateService.get("draft") } returns state("draft", WorkflowStateType.DRAFT)
        coEvery { stateService.get("published") } returns null
        val pending = metadata(stateId = "draft")
        coEvery { metadataService.setPendingState(any(), any(), any(), any(), any(), any()) } returns pending

        val ex = assertFailsWith<IllegalStateException> {
            transitioner.beginTransition(auth, request, item)
        }
        assertEquals("state doesn't exist: published", ex.message)
        coVerify { metadataService.setPendingStateFailed(pending, any(), principal = any()) }
    }

    @Test
    fun `beginTransition skips the readiness guards when the item workflow state cannot be resolved`() = runTest {
        // stateService.get(item state) returns null, so both readiness guards short-circuit on
        // `state == null` even though the item is not ready. The EXIT phase then resolves its own
        // targetState from the (same) item state id, which is also null -> "state doesn't exist".
        // This is the only observable outcome of the null-item-state path: the guards are bypassed
        // but the phase cannot resolve the item's current state, so it fails before enqueuing.
        val item = metadata(stateId = "draft", ready = null)
        val request = BeginTransitionInput(metadataId = item.id, version = 1, stateId = "published", status = "s")
        coEvery { transitionService.get("draft", "published") } returns transition()
        coEvery { stateService.get("draft") } returns null
        coEvery { stateService.get("published") } returns state("published", WorkflowStateType.PUBLISHED, jobName = "job-pub")
        val pending = metadata(stateId = "draft", ready = null)
        coEvery { metadataService.setPendingState(any(), any(), any(), any(), any(), any()) } returns pending

        val ex = assertFailsWith<IllegalStateException> {
            transitioner.beginTransition(auth, request, item)
        }
        assertEquals("state doesn't exist: draft", ex.message)
        // failure occurred after setPendingState succeeded -> rollback via setPendingStateFailed
        coVerify { metadataService.setPendingStateFailed(pending, any(), principal = any()) }
    }

    // ── enqueuePhase job-name resolution + buildJobConfiguration ──────────────

    @Test
    fun `beginTransition enqueues exit enter and default jobs immediately`() = runTest {
        // enter/exit job names present on the transition, plus a default job on the target state.
        val item = collection(stateId = "draft", attributes = JsonObject(mapOf("k" to JsonPrimitive("v"))))
        val request = BeginTransitionInput(collectionId = item.id, stateId = "published", status = "s", languageTag = "en")
        coEvery { transitionService.get("draft", "published") } returns transition(
            enter = "enter-job",
            exit = "exit-job",
            configuration = JsonObject(mapOf("tcfg" to JsonPrimitive(1))),
        )
        coEvery { stateService.get("draft") } returns state("draft", WorkflowStateType.DRAFT)
        coEvery { stateService.get("published") } returns state(
            "published",
            WorkflowStateType.PUBLISHED,
            jobName = "default-job",
            configuration = JsonObject(mapOf("scfg" to JsonPrimitive(2))),
        )
        val exit = registerEnqueuer("exit-job")
        val enter = registerEnqueuer("enter-job")
        val def = registerEnqueuer("default-job")
        val pending = collection(stateId = "draft")
        coEvery { collectionService.setPendingState(any(), any(), any(), any(), any(), any()) } returns pending
        coEvery { collectionJobHistory.addHistory(any()) } answers { firstArg() }

        val result = transitioner.beginTransition(auth, request, item)

        assertSame(pending, result)
        coVerify { exit.enqueue(any(), any()) }
        coVerify { enter.enqueue(any(), any()) }
        coVerify { def.enqueue(any(), any()) }
        // three history rows written (exit, enter, default)
        coVerify(exactly = 3) { collectionJobHistory.addHistory(any()) }
    }

    @Test
    fun `beginTransition skips exit and enter when no job names configured and builds default with null transition config and JsonNull state config`() = runTest {
        // No enter/exit job names -> those phases return null (no enqueue).
        // transition.configuration null -> JsonObject(emptyMap()) fallback.
        // state configuration JsonNull -> takeIf drops it.
        // languageTag null -> languageTagEntries empty branch.
        val item = metadata(stateId = "draft", attributes = null)
        val request = BeginTransitionInput(metadataId = item.id, version = 1, stateId = "published", status = "s")
        coEvery { transitionService.get("draft", "published") } returns transition(enter = null, exit = null, configuration = null)
        coEvery { stateService.get("draft") } returns state("draft", WorkflowStateType.DRAFT)
        coEvery { stateService.get("published") } returns state(
            "published",
            WorkflowStateType.PUBLISHED,
            jobName = "default-job",
            configuration = JsonNull,
        )
        val def = registerEnqueuer("default-job")
        val pending = metadata(stateId = "draft")
        coEvery { metadataService.setPendingState(any(), any(), any(), any(), any(), any()) } returns pending
        coEvery { metadataJobHistory.addHistory(any()) } answers { firstArg() }

        val result = transitioner.beginTransition(auth, request, item)

        assertSame(pending, result)
        coVerify(exactly = 1) { def.enqueue(any(), any()) }
        coVerify(exactly = 1) { metadataJobHistory.addHistory(any()) }
    }

    @Test
    fun `beginTransition uses fallback job name when target state has no default job`() = runTest {
        // targetState.jobName null on DEFAULT phase -> resolvedJobName falls back to ops.fallbackJobName.
        val item = metadata(stateId = "draft")
        val request = BeginTransitionInput(metadataId = item.id, version = 1, stateId = "published", status = "s")
        coEvery { transitionService.get("draft", "published") } returns transition(enter = null, exit = null)
        coEvery { stateService.get("draft") } returns state("draft", WorkflowStateType.DRAFT)
        coEvery { stateService.get("published") } returns state("published", WorkflowStateType.PUBLISHED, jobName = null)
        val fallback = registerEnqueuer("transition-metadata")
        val pending = metadata(stateId = "draft")
        coEvery { metadataService.setPendingState(any(), any(), any(), any(), any(), any()) } returns pending
        coEvery { metadataJobHistory.addHistory(any()) } answers { firstArg() }

        val result = transitioner.beginTransition(auth, request, item)

        assertSame(pending, result)
        coVerify { fallback.enqueue(any(), any()) }
    }

    // ── delayed enqueue path ─────────────────────────────────────────────────

    @Test
    fun `beginTransition schedules the job for later when stateValid is in the future`() = runTest {
        val future = OffsetDateTime.now().plusHours(1)
        val item = metadata(stateId = "draft")
        val request = BeginTransitionInput(
            metadataId = item.id,
            version = 1,
            stateId = "published",
            status = "s",
            stateValid = future,
        )
        coEvery { transitionService.get("draft", "published") } returns transition(enter = null, exit = null)
        coEvery { stateService.get("draft") } returns state("draft", WorkflowStateType.DRAFT)
        coEvery { stateService.get("published") } returns state("published", WorkflowStateType.PUBLISHED, jobName = "job-pub")
        val def = registerEnqueuer("job-pub")
        val pending = metadata(stateId = "draft")
        coEvery { metadataService.setPendingState(any(), any(), any(), any(), any(), any()) } returns pending
        coEvery { metadataJobHistory.addHistory(any()) } answers { firstArg() }

        val result = transitioner.beginTransition(auth, request, item)

        assertSame(pending, result)
        // delay is in the future -> enqueueLater, not enqueue
        coVerify { def.enqueueLater(any(), any(), any()) }
    }

    // ── rollback-on-failure ──────────────────────────────────────────────────

    @Test
    fun `beginTransition rolls back pending state when enqueue fails`() = runTest {
        // No enqueuer registered for the resolved job name -> provide<> throws MissingProviderException
        // inside enqueuePhase, after setPendingState succeeded.
        val item = metadata(stateId = "draft")
        val request = BeginTransitionInput(metadataId = item.id, version = 1, stateId = "published", status = "s")
        coEvery { transitionService.get("draft", "published") } returns transition(enter = null, exit = null)
        coEvery { stateService.get("draft") } returns state("draft", WorkflowStateType.DRAFT)
        coEvery { stateService.get("published") } returns state("published", WorkflowStateType.PUBLISHED, jobName = "missing-job")
        val pending = metadata(stateId = "draft")
        coEvery { metadataService.setPendingState(any(), any(), any(), any(), any(), any()) } returns pending

        assertFailsWith<MissingProviderException> {
            transitioner.beginTransition(auth, request, item)
        }
        coVerify { metadataService.setPendingStateFailed(pending, any(), principal = any()) }
    }

    @Test
    fun `beginTransition logs and rethrows when rollback itself fails`() = runTest {
        val item = metadata(stateId = "draft")
        val request = BeginTransitionInput(metadataId = item.id, version = 1, stateId = "published", status = "s")
        coEvery { transitionService.get("draft", "published") } returns transition(enter = null, exit = null)
        coEvery { stateService.get("draft") } returns state("draft", WorkflowStateType.DRAFT)
        coEvery { stateService.get("published") } returns state("published", WorkflowStateType.PUBLISHED, jobName = "missing-job")
        val pending = metadata(stateId = "draft")
        coEvery { metadataService.setPendingState(any(), any(), any(), any(), any(), any()) } returns pending
        // Rollback itself throws -> the catch's inner try logs and the ORIGINAL exception propagates.
        coEvery { metadataService.setPendingStateFailed(any(), any(), principal = any()) } throws RuntimeException("rollback boom")

        // The original MissingProviderException (not the rollback failure) is rethrown.
        assertFailsWith<MissingProviderException> {
            transitioner.beginTransition(auth, request, item)
        }
    }

    @Test
    fun `beginTransition rolls back with default message when exception has no message`() = runTest {
        val item = metadata(stateId = "draft")
        val request = BeginTransitionInput(metadataId = item.id, version = 1, stateId = "published", status = "s")
        coEvery { transitionService.get("draft", "published") } returns transition(enter = null, exit = null)
        coEvery { stateService.get("draft") } returns state("draft", WorkflowStateType.DRAFT)
        coEvery { stateService.get("published") } returns state("published", WorkflowStateType.PUBLISHED, jobName = "job-pub")
        val pending = metadata(stateId = "draft")
        coEvery { metadataService.setPendingState(any(), any(), any(), any(), any(), any()) } returns pending
        // enqueue throws with a null message -> falls back to "Transition failed"
        val enqueuer = mockk<JobConfigurationEnqueuer>(relaxed = true)
        coEvery { enqueuer.enqueue(any(), any()) } throws RuntimeException()
        provides<JobConfigurationEnqueuer>(name = "job-pub", singleton = true) { enqueuer }

        assertFailsWith<RuntimeException> {
            transitioner.beginTransition(auth, request, item)
        }
        coVerify { metadataService.setPendingStateFailed(pending, "Transition failed", principal = any()) }
    }

    // ── cancelLatestJob ──────────────────────────────────────────────────────

    @Test
    fun `cancelLatestJob for metadata cancels active jobs and fails pending state`() = runTest {
        val item = metadata(stateId = "pending")
        coEvery { metadataService.getById(item.id, 1) } returns item
        coEvery { metadataJobHistory.getActiveJobs(item.id, 1) } returns emptyList()

        transitioner.cancelLatestJob(auth, metadataId = item.id, metadataVersion = 1)

        // pending state -> setNotReady is called, then pending failed
        coVerify { metadataService.setNotReady(item) }
        coVerify { metadataService.setPendingStateFailed(item, "Cancelled Transition", principal = any()) }
    }

    @Test
    fun `cancelLatestJob for collection cancels active jobs and fails pending state`() = runTest {
        val item = collection(stateId = "draft")
        coEvery { collectionService.getById(item.id) } returns item
        coEvery { collectionJobHistory.getActiveJobs(item.id) } returns emptyList()

        transitioner.cancelLatestJob(auth, collectionId = item.id)

        // not pending -> setNotReady NOT called for the pending branch; only failed
        coVerify { collectionService.setPendingStateFailed(item, "Cancelled Transition", principal = any()) }
    }

    @Test
    fun `cancelLatestJob for collection language variant resolves the variant`() = runTest {
        val variant = languageVariant(stateId = "draft", languageTag = "es")
        coEvery { collectionService.getLanguageVariant(variant.id, "es") } returns variant
        coEvery { collectionJobHistory.getActiveJobs(variant.id) } returns emptyList()

        transitioner.cancelLatestJob(auth, collectionId = variant.id, languageTag = "es")

        coVerify { collectionService.setPendingStateFailed(variant, "Cancelled Transition", principal = any()) }
    }

    @Test
    fun `cancelLatestJob active job is cancelled via its enqueuer queue`() = runTest {
        val item = collection(stateId = "draft")
        val jobId = UUID.random()
        val activeJob = CollectionJobHistory(
            id = item.id,
            jobName = "job-pub",
            jobId = jobId,
            status = "running",
            principal = principal.id,
        )
        coEvery { collectionService.getById(item.id) } returns item
        coEvery { collectionJobHistory.getActiveJobs(item.id) } returns listOf(activeJob)
        val enqueuer = registerEnqueuer("job-pub")
        val queue = mockk<bosca.sharedqueue.jobs.JobQueue>(relaxed = true)
        coEvery { enqueuer.queue() } returns queue

        transitioner.cancelLatestJob(auth, collectionId = item.id)

        coVerify { queue.markCancelled(jobId) }
        coVerify { collectionJobHistory.setComplete(item.id, jobId, "Cancelled", false) }
        coVerify { collectionService.setPendingStateFailed(item, "Cancelled Transition", principal = any()) }
    }
}
