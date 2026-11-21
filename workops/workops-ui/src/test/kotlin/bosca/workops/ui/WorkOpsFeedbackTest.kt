package bosca.workops.ui

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*

class WorkOpsFeedbackTest {
    @Test fun `identical successes issue distinct notices while quiet reads and failures do not`() = runTest {
        val feedback = WorkOpsFeedback()
        val identity = feedback.noticeId
        repeat(2) { feedback.perform { feedback.message = "Portfolio saved." } }
        assertEquals(2, feedback.noticeSequence)
        feedback.perform { }
        assertEquals("", feedback.message)
        assertEquals(2, feedback.noticeSequence)
        feedback.perform { throw IllegalStateException("Try again") }
        assertTrue(feedback.failed)
        assertEquals(2, feedback.noticeSequence)
        feedback.perform { feedback.message = "Portfolio saved." }
        assertEquals(3, feedback.noticeSequence)
        assertEquals(identity, feedback.noticeId)
    }

    @Test fun `cancellation propagates without issuing an action notice`() = runTest {
        val feedback = WorkOpsFeedback()
        assertFailsWith<CancellationException> { feedback.perform { throw CancellationException("Cancelled") } }
        assertEquals(0, feedback.noticeSequence)
        assertFalse(feedback.failed)
    }

    @Test fun `explicit creation notices retain their identity through serialized island state`() {
        val feedback = WorkOpsFeedback(message = "Previous failure", noticeId = "retained-creation-notice", noticeSequence = 4)
        feedback.failed = true
        feedback.success("Task created.")
        assertFalse(feedback.failed)
        assertEquals(5, feedback.noticeSequence)
        for (json in listOf(Json, Json { encodeDefaults = true })) {
            val decoded = json.decodeFromString(WorkOpsFeedback.serializer(), json.encodeToString(WorkOpsFeedback.serializer(), feedback))
            assertEquals(feedback.noticeId, decoded.noticeId)
            assertEquals(5, decoded.noticeSequence)
            assertEquals("Task created.", decoded.message)
        }
        val initial = Json.decodeFromString(WorkOpsFeedback.serializer(), "{}")
        assertTrue(initial.noticeId.isNotBlank())
        assertEquals(0, initial.noticeSequence)
        assertEquals("", initial.message)
        assertFalse(initial.failed)
        val initialTask = Json.decodeFromString(WorkOpsTaskCreateModel.serializer(), "{}")
        assertEquals(0, initialTask.feedback.noticeSequence)
        val initialApproval = Json.decodeFromString(WorkOpsApprovalModel.serializer(), "{\"id\":\"run\"}")
        assertEquals(0, initialApproval.acknowledgementSequence)
    }

    @Test fun `task creation and approval retain issued notices independently of later failures`() {
        val creation = WorkOpsTaskCreateModel(createdId = "created-task")
        creation.feedback.success("Task created.")
        val restored = Json.decodeFromString(WorkOpsTaskCreateModel.serializer(), Json.encodeToString(WorkOpsTaskCreateModel.serializer(), creation))
        assertEquals(creation.feedback.noticeId, restored.feedback.noticeId)
        assertEquals(1, restored.feedback.noticeSequence)
        assertEquals("created-task", restored.createdId)
        val approval = WorkOpsApprovalModel("run", acknowledgement = "Approval recorded.",
            feedback = WorkOpsFeedback("Reload failed", true), acknowledgementSequence = 1)
        val decision = Json.decodeFromString(WorkOpsApprovalModel.serializer(), Json.encodeToString(WorkOpsApprovalModel.serializer(), approval))
        assertEquals("Approval recorded.", decision.acknowledgement)
        assertEquals(1, decision.acknowledgementSequence)
        assertTrue(decision.feedback.failed)
        assertEquals("Reload failed", decision.feedback.message)
    }

    @Test fun `a blank upstream error remains a visible failure and a successful retry clears it`() = runTest {
        val feedback = WorkOpsFeedback()
        feedback.perform { throw IllegalStateException("") }
        assertTrue(feedback.failed); assertTrue(feedback.message.isNotBlank())
        var retried = false
        feedback.perform { retried = true }
        assertTrue(retried); assertFalse(feedback.failed); assertEquals("", feedback.message)
    }
}
