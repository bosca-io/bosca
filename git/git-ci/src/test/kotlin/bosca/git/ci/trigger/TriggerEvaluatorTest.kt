package bosca.git.ci.trigger

import bosca.git.model.Pipeline
import bosca.git.ci.toJsonElement
import bosca.git.model.PipelineTrigger
import bosca.git.ci.toJsonElement
import bosca.git.model.PipelineTriggerType
import bosca.git.model.PushEvent
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TriggerEvaluatorTest {

    private val evaluator = TriggerEvaluator()
    private val repoId = UUID.random()

    private fun pipeline(vararg triggers: PipelineTrigger) = Pipeline(
        repositoryId = repoId,
        filePath = ".bosca/pipelines/test.yaml",
        name = "Test",
        triggers = triggers.toList().toJsonElement(),
        configHash = "abc123"
    )

    private fun pushEvent(ref: String = "refs/heads/main") = PushEvent(
        repositoryId = repoId,
        ref = ref,
        beforeSha = "0000000",
        afterSha = "1111111"
    )

    @Test
    fun `matches push with no branch filter`() {
        val p = pipeline(PipelineTrigger(type = PipelineTriggerType.PUSH))
        assertNotNull(evaluator.evaluatePush(p, pushEvent()))
    }

    @Test
    fun `matches push with matching branch`() {
        val p = pipeline(PipelineTrigger(type = PipelineTriggerType.PUSH, branches = listOf("main")))
        assertNotNull(evaluator.evaluatePush(p, pushEvent("refs/heads/main")))
    }

    @Test
    fun `rejects push with non-matching branch`() {
        val p = pipeline(PipelineTrigger(type = PipelineTriggerType.PUSH, branches = listOf("main")))
        assertNull(evaluator.evaluatePush(p, pushEvent("refs/heads/develop")))
    }

    @Test
    fun `matches push with glob branch pattern`() {
        val p = pipeline(PipelineTrigger(type = PipelineTriggerType.PUSH, branches = listOf("release/*")))
        assertNotNull(evaluator.evaluatePush(p, pushEvent("refs/heads/release/1.0")))
        assertNull(evaluator.evaluatePush(p, pushEvent("refs/heads/main")))
    }

    @Test
    fun `matches push with path filter`() {
        val p = pipeline(PipelineTrigger(type = PipelineTriggerType.PUSH, paths = listOf("src/**")))
        assertNotNull(evaluator.evaluatePush(p, pushEvent(), listOf("src/main/App.kt")))
        assertNull(evaluator.evaluatePush(p, pushEvent(), listOf("docs/README.md")))
    }

    @Test
    fun `excludes push with paths-ignore`() {
        val p = pipeline(PipelineTrigger(
            type = PipelineTriggerType.PUSH,
            pathsIgnore = listOf("docs/**", "*.md")
        ))
        assertNull(evaluator.evaluatePush(p, pushEvent(), listOf("docs/guide.md")))
        assertNotNull(evaluator.evaluatePush(p, pushEvent(), listOf("src/App.kt")))
    }

    @Test
    fun `ignores tag refs for push triggers`() {
        val p = pipeline(PipelineTrigger(type = PipelineTriggerType.PUSH))
        assertNull(evaluator.evaluatePush(p, pushEvent("refs/tags/v1.0")))
    }

    @Test
    fun `matches tag trigger`() {
        val p = pipeline(PipelineTrigger(type = PipelineTriggerType.TAG, tags = listOf("v*")))
        assertNotNull(evaluator.evaluateTag(p, "v1.0.0"))
        assertNull(evaluator.evaluateTag(p, "release-1.0"))
    }

    @Test
    fun `matches tag trigger with no pattern`() {
        val p = pipeline(PipelineTrigger(type = PipelineTriggerType.TAG))
        assertNotNull(evaluator.evaluateTag(p, "anything"))
    }

    @Test
    fun `detects manual trigger`() {
        val p = pipeline(PipelineTrigger(type = PipelineTriggerType.MANUAL))
        assertTrue(evaluator.supportsManualTrigger(p))
    }

    @Test
    fun `no manual trigger when not configured`() {
        val p = pipeline(PipelineTrigger(type = PipelineTriggerType.PUSH))
        assertFalse(evaluator.supportsManualTrigger(p))
    }

    @Test
    fun `glob matching with double star`() {
        assertTrue(TriggerEvaluator.matchesGlob("src/**", "src/main/kotlin/App.kt"))
        assertTrue(TriggerEvaluator.matchesGlob("**/*.kt", "src/main/App.kt"))
        assertFalse(TriggerEvaluator.matchesGlob("src/*", "src/main/App.kt"))
    }

    @Test
    fun `glob matching with single star`() {
        assertTrue(TriggerEvaluator.matchesGlob("*.md", "README.md"))
        assertFalse(TriggerEvaluator.matchesGlob("*.md", "docs/README.md"))
    }

    @Test
    fun `glob matching with question mark`() {
        assertTrue(TriggerEvaluator.matchesGlob("file?.txt", "file1.txt"))
        assertFalse(TriggerEvaluator.matchesGlob("file?.txt", "file12.txt"))
    }
}
