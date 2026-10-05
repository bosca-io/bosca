package bosca.kubernetes.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Best-effort log-level inference for unstructured log lines.
 *
 * The contract is forgiving by design — every Kubernetes operator runs a mix
 * of structured-JSON, glog, logrus, and bracketed-prefix loggers, and the
 * studio's log viewer needs *some* level signal to colour rows even when
 * applications don't agree on a format. INFO is the documented fall-through.
 */
class LogLevelInferenceTest {

    @Test
    fun `classifies JSON-shaped error and warn levels`() {
        assertEquals(EventLevel.ERROR, LogLevelInference.classify("""{"level":"error","msg":"boom"}"""))
        assertEquals(EventLevel.ERROR, LogLevelInference.classify("""{"level":"fatal"}"""))
        assertEquals(EventLevel.ERROR, LogLevelInference.classify("""{"level":"panic"}"""))
        assertEquals(EventLevel.WARN, LogLevelInference.classify("""{"level":"warn","msg":"x"}"""))
        assertEquals(EventLevel.WARN, LogLevelInference.classify("""{"level":"warning"}"""))
    }

    @Test
    fun `classifies logrus and glog key-value markers`() {
        assertEquals(EventLevel.ERROR, LogLevelInference.classify("time=2026 level=error msg=x"))
        assertEquals(EventLevel.ERROR, LogLevelInference.classify("level=fatal"))
        assertEquals(EventLevel.WARN, LogLevelInference.classify("level=warn x=y"))
        assertEquals(EventLevel.WARN, LogLevelInference.classify("level=warning"))
    }

    @Test
    fun `classifies bracketed prefixes case-insensitively`() {
        assertEquals(EventLevel.ERROR, LogLevelInference.classify("[ERROR] application failed"))
        assertEquals(EventLevel.ERROR, LogLevelInference.classify("[fatal] panic"))
        assertEquals(EventLevel.WARN, LogLevelInference.classify("[WARN] degraded"))
        assertEquals(EventLevel.WARN, LogLevelInference.classify("[Warning] slow query"))
    }

    @Test
    fun `classifies word-start markers`() {
        assertEquals(EventLevel.ERROR, LogLevelInference.classify("panic: runtime nil pointer"))
        assertEquals(EventLevel.ERROR, LogLevelInference.classify("fatal: cannot bind socket"))
        assertEquals(EventLevel.ERROR, LogLevelInference.classify("error reading config"))
        assertEquals(EventLevel.WARN, LogLevelInference.classify("warn: deprecation notice"))
        assertEquals(EventLevel.INFO, LogLevelInference.classify("info: starting up"))
        assertEquals(EventLevel.INFO, LogLevelInference.classify("debug: trace"))
    }

    @Test
    fun `falls back to INFO on unrecognised lines`() {
        assertEquals(EventLevel.INFO, LogLevelInference.classify(""))
        assertEquals(EventLevel.INFO, LogLevelInference.classify("just some ordinary log output"))
        assertEquals(EventLevel.INFO, LogLevelInference.classify("Listening on :8080"))
    }

    @Test
    fun `does not promote level for stray error mentions inside info messages`() {
        // The structurally-specific patterns are checked before the loose
        // word-start fallback, so a sentence that *mentions* "error" without
        // any structural marker stays at INFO.
        assertEquals(EventLevel.INFO, LogLevelInference.classify("validating against the schema"))
        // But a line that *starts* with "error" promotes — that's the intended
        // policy: when the application led with the word, trust it.
        assertEquals(EventLevel.ERROR, LogLevelInference.classify("error reading config: file not found"))
    }
}
