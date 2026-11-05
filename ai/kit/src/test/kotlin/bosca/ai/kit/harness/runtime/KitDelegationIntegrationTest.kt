package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.*
import bosca.db.transaction
import bosca.serialization.UUID
import bosca.serialization.OffsetDateTime
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import io.mockk.*
import kotlin.test.*

class KitDelegationIntegrationTest {
    @Test fun `child admission is atomic idempotent isolated and bounded by runtime authority`(): Unit = runBlocking {
        KitDelegationFixture().use { f ->
            f.initialize()
            f.request {
                val input = f.input().copy(artifacts = listOf(f.source.id), scopes = listOf("content:view"))
                val child = f.service.start(f.auth(), f.sessionId, input)
                assertEquals(child, f.service.start(f.auth(), f.sessionId, input))
                assertEquals(f.turn.id, child.parentTurnId); assertEquals(f.turn.id, child.rootTurnId)
                assertEquals(listOf("content:view"), child.authority.scopes)
                val chat = assertNotNull(f.history.getSession(child.childSessionId))
                assertEquals(f.sessionId, chat.parentSessionId)
                assertFalse(f.history.getMessages(child.childSessionId).any { it.event.toString().contains("Parent private history") })
                assertEquals(listOf(f.sessionId), f.history.getSessions(f.principalId).map { it.id })
                assertEquals(listOf(child.childSessionId), f.history.getChildSessions(f.sessionId).map { it.id })
                assertEquals("QUEUED", f.runtimes.turn(child.childTurnId)?.status)
                assertNull(f.runtimes.get(child.childSessionId)?.credentialId)
                assertEquals(f.source.id.toString(), child.contextManifest?.sources?.single()?.id)
                assertTrue(child.deadline <= OffsetDateTime.now().plusSeconds(30))
                val settings = assertNotNull(f.service.resolution(assertNotNull(f.runtimes.get(child.childSessionId))))
                assertEquals("claude", settings.configuration.profile)
                assertTrue(settings.configuration.hardRuntimeSeconds <= input.runtimeSeconds)
                assertFailsWith<IllegalArgumentException> { f.service.start(f.auth(), f.sessionId, input.copy(brief = "Different")) }
                assertFailsWith<IllegalArgumentException> { f.service.start(f.auth(), f.sessionId, f.input().copy(scopes = listOf("ai:execute"))) }
                assertFailsWith<bosca.security.service.SecurityException> { f.service.start(f.auth(999), f.sessionId, f.input()) }
                assertFailsWith<bosca.security.service.SecurityException> { f.service.start(f.auth(tokenScopes = listOf("content:view")), f.sessionId, f.input()) }
                assertFailsWith<IllegalArgumentException> { f.service.start(f.auth(), f.sessionId, f.input().copy(deadline = OffsetDateTime.now().minusSeconds(1))) }
                f.sourceAllowed = false
                assertFailsWith<IllegalArgumentException> { f.service.start(f.auth(), f.sessionId, f.input().copy(artifacts = listOf(f.source.id))) }
                assertEquals(1, f.history.getChildSessions(f.sessionId).size, "Denied admission rolls back its child conversation")
                assertEquals(1, f.repository.tree(f.turn.id).size)
                f.sourceAllowed = true
                f.sourceBody = "Changed after admission"
                val runtime = assertNotNull(f.runtimes.get(child.childSessionId))
                assertFailsWith<IllegalArgumentException> { f.service.validateContext(child.childSessionId, f.context.resolve(runtime).manifest) }
            }
        }
    }

    @Test fun `concurrent admissions serialize root reservations and descendants cannot escape budgets`(): Unit = runBlocking {
        KitDelegationFixture().use { f ->
            f.initialize()
            val admitted = coroutineScope { (1..4).map { async(Dispatchers.IO) {
                runCatching { f.request { f.service.start(f.auth(), f.sessionId, f.input(40)) } }
            } }.awaitAll() }
            assertEquals(2, admitted.count { it.isSuccess })
            assertEquals(2, admitted.count { it.exceptionOrNull() is IllegalArgumentException })
            f.request {
                val children = f.repository.tree(f.turn.id)
                val first = children.first()
                val second = children.last()
                f.service.finish(assertNotNull(f.runtimes.get(second.childSessionId)), assertNotNull(f.runtimes.turn(second.childTurnId)), "FAILED", "Partial investigation")
                f.sent(first.childSessionId, 202)
                val grandchild = f.service.start(f.auth(202), first.childSessionId, f.input(40))
                assertEquals(2, grandchild.depth); assertEquals(f.turn.id, grandchild.rootTurnId)
                f.service.finish(assertNotNull(f.runtimes.get(grandchild.childSessionId)), assertNotNull(f.runtimes.turn(grandchild.childTurnId)), "COMPLETED", "Answer")
                assertFailsWith<IllegalArgumentException> { f.service.start(f.auth(), f.sessionId, f.input(1)) }
                assertEquals(120, f.repository.tree(f.turn.id).sumOf { it.input.runtimeSeconds })
                f.sent(grandchild.childSessionId, 303)
                assertFailsWith<IllegalArgumentException> { f.service.start(f.auth(303), grandchild.childSessionId, f.input(1)) }
                assertEquals(3, f.repository.tree(f.turn.id).size)
            }
        }
    }

    @Test fun `typed output stays partial until receipt commits once and current permissions gate results`(): Unit = runBlocking {
        KitDelegationFixture().use { f ->
            f.initialize()
            f.request {
                val child = f.service.start(f.auth(), f.sessionId, f.input())
                f.sent(child.childSessionId, 202)
                val result = KitDelegationResult("Found the answer", listOf(KitDelegationArtifact(f.source.id, "Evidence")))
                val proposed = f.service.submit(f.auth(202), child.childSessionId, result)
                assertEquals(result, proposed.result); assertFalse(proposed.status.terminal)
                assertEquals(proposed, f.service.submit(f.auth(202), child.childSessionId, result))
                assertFailsWith<IllegalArgumentException> { f.service.submit(f.auth(202), child.childSessionId, result.copy(summary = "Changed")) }
                assertFailsWith<bosca.security.service.SecurityException> { f.service.submit(f.auth(), child.childSessionId, result) }
                val childRuntime = assertNotNull(f.runtimes.get(child.childSessionId))
                val childTurn = assertNotNull(f.runtimes.turn(child.childTurnId))
                f.service.wait(f.auth(), f.sessionId, child.id)
                assertTrue(f.service.waiting(f.turn.id))
                transaction { f.service.finish(childRuntime, childTurn, "COMPLETED", "Native fallback") }
                f.service.finish(childRuntime, childTurn, "FAILED", "Duplicate delivery")
                assertFalse(f.service.waiting(f.turn.id))
                assertEquals(KitDelegationStatus.COMPLETED, f.service.get(f.auth(), f.sessionId, child.id)?.status)
                assertEquals(result, f.service.get(f.auth(), f.sessionId, child.id)?.result)
                assertEquals(1, f.history.getMessages(f.sessionId).count { it.event.toString().contains("Found the answer") })
                f.sourceAllowed = false
                val denied = assertNotNull(f.service.get(f.auth(), f.sessionId, child.id))
                assertNull(denied.result); assertContains(assertNotNull(denied.failure), "unavailable")
                f.sourceAllowed = true
                assertEquals(result, f.service.list(f.auth(), f.sessionId, 0, 1).single().result)
                f.groups = emptyList()
                assertFailsWith<bosca.security.service.SecurityException> { f.service.get(f.auth(), f.sessionId, child.id) }
            }
        }
    }

    @Test fun `cancellation failure deletion and new parent turns resolve durable waits without losing reservations`(): Unit = runBlocking {
        KitDelegationFixture().use { f ->
            f.initialize()
            f.request {
                val first = f.service.start(f.auth(), f.sessionId, f.input())
                val second = f.service.start(f.auth(), f.sessionId, f.input())
                f.runtimes.finish(f.turn.id, "COMPLETED", null)
                val next = f.sent(f.sessionId, 101)
                f.service.wait(f.auth(), f.sessionId, first.id)
                assertFalse(f.service.waiting(f.turn.id)); assertTrue(f.service.waiting(next.id))
                assertEquals(next.id, f.repository.get(first.id)?.waitTurnId)
                f.service.cancel(f.auth(), f.sessionId, first.id)
                assertEquals(KitDelegationStatus.CANCELLED, f.service.get(f.auth(), f.sessionId, first.id)?.status)
                assertFalse(f.service.waiting(next.id))
                val failed = assertNotNull(f.runtimes.get(second.childSessionId))
                f.runtimes.save(failed.copy(state = "FAILED", failure = "Startup identity mismatch"))
                assertEquals(KitDelegationStatus.FAILED, f.service.get(f.auth(), f.sessionId, second.id)?.status)
                assertContains(assertNotNull(f.service.get(f.auth(), f.sessionId, second.id)?.failure), "Startup identity mismatch")
                val queued = f.service.start(f.auth(), f.sessionId, f.input())
                f.harness.delete(queued.childSessionId)
                f.service.reconcile(assertNotNull(f.runtimes.get(queued.childSessionId)))
                assertEquals(KitDelegationStatus.CANCELLED, f.repository.get(queued.id)?.status)
                assertEquals(2, f.repository.tree(f.turn.id).size, "Prior turn budgets remain reserved")
                f.harness.delete(f.sessionId)
                assertTrue(f.history.getChildSessions(f.sessionId).all { f.runtimes.get(it.id)?.deletedAt != null })
                assertFalse(f.service.childrenStopped(f.sessionId))
            }
        }
    }

    @Test fun `fixed deadline distinguishes timeout from early explicit cancellation and preserves partial evidence`(): Unit = runBlocking {
        KitDelegationFixture().use { f ->
            f.initialize()
            f.request {
                val deadline = OffsetDateTime.now().plusSeconds(1)
                val expired = f.service.start(f.auth(), f.sessionId, f.input().copy(deadline = deadline))
                val early = f.service.start(f.auth(), f.sessionId, f.input().copy(deadline = deadline))
                f.sent(expired.childSessionId, 202)
                f.service.submit(f.auth(202), expired.childSessionId, KitDelegationResult("Partial evidence"))
                val first = assertNotNull(f.runtimes.get(expired.childSessionId))
                val second = assertNotNull(f.runtimes.get(early.childSessionId)).copy(cancelRequestedAt = OffsetDateTime.now())
                delay(1100)
                f.service.finish(first, assertNotNull(f.runtimes.turn(expired.childTurnId)), "CANCELLED", "")
                f.service.finish(second, assertNotNull(f.runtimes.turn(early.childTurnId)), "CANCELLED", "")
                assertEquals(KitDelegationStatus.TIMED_OUT, f.repository.get(expired.id)?.status)
                assertEquals("Partial evidence", f.repository.get(expired.id)?.result?.summary)
                assertEquals(KitDelegationStatus.CANCELLED, f.repository.get(early.id)?.status)
                assertContains(assertNotNull(f.repository.get(expired.id)?.failure), "completed actions were not undone")
            }
        }
    }

    @Test fun `depth and total child count each reject independently while completed reservations remain`(): Unit = runBlocking {
        KitDelegationFixture().use { f ->
            f.limits = f.limits.copy(maxDepth = 1, maxChildren = 2, totalRuntimeSeconds = 180)
            f.initialize()
            f.request {
                val first = f.service.start(f.auth(), f.sessionId, f.input())
                f.sent(first.childSessionId, 202)
                assertFailsWith<IllegalArgumentException> { f.service.start(f.auth(202), first.childSessionId, f.input()) }
                assertEquals(1, f.repository.tree(f.turn.id).size)
                f.service.finish(assertNotNull(f.runtimes.get(first.childSessionId)), assertNotNull(f.runtimes.turn(first.childTurnId)), "COMPLETED", "First answer")
                val second = f.service.start(f.auth(), f.sessionId, f.input())
                f.service.finish(assertNotNull(f.runtimes.get(second.childSessionId)), assertNotNull(f.runtimes.turn(second.childTurnId)), "FAILED", "")
                assertEquals(60, f.repository.tree(f.turn.id).sumOf { it.input.runtimeSeconds })
                assertFailsWith<IllegalArgumentException> { f.service.start(f.auth(), f.sessionId, f.input()) }
                assertEquals(2, f.history.getChildSessions(f.sessionId).size)
            }
        }
    }

    @Test fun `a managed default Git ref cannot stand in for an unavailable explicitly selected ref`(): Unit = runBlocking {
        KitDelegationFixture(listOf("ai:delegate", "content:view", "git:read")).use { f ->
            f.initialize()
            val repository = bosca.git.model.Repository(id = UUID.random(), ownerId = UUID.random(), slug = "plans", name = "Plans")
            val selected = KitContextSelection(KitContextSourceKind.GIT_FILE, repository.id, "main", "docs/plan.md")
            val agent = bosca.ai.agents.model.Agent(UUID.random(), "kit", "Kit", "", UUID.random(), UUID.random(), configuration = buildJsonObject {
                put("harnessContext", f.json.encodeToJsonElement(KitContextConfiguration.serializer(), KitContextConfiguration(sources = listOf(selected.copy(ref = "release")))))
            })
            coEvery { f.agents.getByKey("kit") } returns agent
            coEvery { f.prompts.get(agent.promptId) } returns bosca.ai.prompts.model.Prompt(agent.promptId, "kit", "Kit", "", "Inspect sources", "", "text", "text", null)
            coEvery { f.repositories.findById(repository.id) } returns repository
            coEvery { f.repositoryPermissions.isAllowed(any(), repository, bosca.security.model.PermissionAction.VIEW) } returns true
            coEvery { f.browse.resolveRef(repository.id, "main") } returns null
            coEvery { f.browse.resolveRef(repository.id, "release") } returns "a".repeat(40)
            coEvery { f.browse.readBlob(repository.id, "a".repeat(40), "docs/plan.md") } returns bosca.git.model.Blob("Release plan", 12, "a".repeat(40))
            f.request {
                assertFailsWith<IllegalArgumentException> { f.service.start(f.auth(), f.sessionId, f.input().copy(context = listOf(selected))) }
                assertTrue(f.history.getChildSessions(f.sessionId).isEmpty())
                assertTrue(f.repository.tree(f.turn.id).isEmpty())
            }
        }
    }

    @Test fun `deleting a sent child retains its concurrency reservation until confirmed cleanup`(): Unit = runBlocking {
        KitDelegationFixture().use { f ->
            f.limits = f.limits.copy(maxActive = 1)
            f.initialize()
            f.request {
                val child = f.service.start(f.auth(), f.sessionId, f.input())
                f.sent(child.childSessionId, 202)
                f.harness.delete(child.childSessionId)
                assertFalse(assertNotNull(f.service.get(f.auth(), f.sessionId, child.id)).status.terminal)
                assertFailsWith<IllegalArgumentException> { f.service.start(f.auth(), f.sessionId, f.input()) }
                f.service.finish(assertNotNull(f.runtimes.get(child.childSessionId)), assertNotNull(f.runtimes.turn(child.childTurnId)), "INTERRUPTED", "")
                assertEquals(KitDelegationStatus.INTERRUPTED, f.repository.get(child.id)?.status)
                assertEquals(KitDelegationStatus.QUEUED, f.service.start(f.auth(), f.sessionId, f.input()).status)
            }
        }
    }
}
