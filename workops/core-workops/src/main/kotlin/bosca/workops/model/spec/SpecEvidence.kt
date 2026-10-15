package bosca.workops.model.spec

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/** A caller-reviewed interface declaration, captured before implementing its behavior. */
@Serializable
data class ReviewedInterface(val path: String, val declaration: String)

/** Commands and observed results for one acceptance criterion's red, green, refactor cycle. */
@Serializable
data class TddEvidence(val red: TddRun, val green: TddRun, val refactor: TddRun) {
    /** Checks recorded phase outcomes; commands and observations are supplied by the caller. */
    fun issues(): List<String> = buildList {
        for ((phase, run, expected) in listOf(
            Triple("RED", red, ValidationOutcome.FAILED),
            Triple("GREEN", green, ValidationOutcome.PASSED),
            Triple("REFACTOR", refactor, ValidationOutcome.PASSED),
        )) {
            if (run.command.isBlank() || run.evidence.isBlank()) add("TDD $phase needs an actual command and observed result")
            if (run.outcome != expected) add("TDD $phase must be $expected")
        }
    }
}

/** RED must fail for the intended missing behavior; GREEN and REFACTOR must pass. */
@Serializable
data class TddRun(
    val command: String,
    val outcome: ValidationOutcome,
    val evidence: String,
)

/** Review of behavioral test quality and coverage reports for every affected production scope. */
@Serializable
data class TestQualityEvidence(val review: String, val coverage: List<CoverageEvidence>) {
    /** Requires a substantive caller review and measured coverage for each reported production scope. */
    fun issues(minimumLine: Double = 99.0, minimumBranch: Double = 97.0): List<String> = buildList {
        if (review.isBlank()) add("Behavioral test quality review is missing")
        if (coverage.isEmpty()) add("Measured production coverage is missing")
        for (report in coverage) addAll(report.issues(minimumLine, minimumBranch).map { "${report.scope}: $it" })
    }
}

/** Raw measured counters; verification derives percentages rather than accepting rounded values. */
@Serializable
data class CoverageEvidence(
    val scope: String,
    val command: String,
    val report: String,
    val lineCovered: Long,
    val lineMissed: Long,
    val branchCovered: Long,
    val branchMissed: Long,
    val uncovered: String,
    val noBranchesReason: String? = null,
) {
    /** Measured percentage, or null when this report has no executable lines. */
    fun linePercent(): Double? = percentage(lineCovered, lineMissed)

    /** Measured percentage, or null when this report has no executable branches. */
    fun branchPercent(): Double? = percentage(branchCovered, branchMissed)

    /** Checks raw counters, report provenance, and the minimum coverage bar without rounding. */
    fun issues(minimumLine: Double = 99.0, minimumBranch: Double = 97.0): List<String> = buildList {
        if (scope.isBlank() || command.isBlank() || report.isBlank() || uncovered.isBlank()) add("Coverage needs scope, actual command, report path, and uncovered-path review")
        if (lineCovered < 0 || lineMissed < 0 || branchCovered < 0 || branchMissed < 0) {
            add("Coverage counters must be nonnegative")
        } else {
            val lines = linePercent()
            if (lines == null) add("Coverage report has no executable lines")
            else if (lines < minimumLine) add("Line coverage $lines% is below $minimumLine%")
            val branches = branchPercent()
            if (branches == null) {
                if (noBranchesReason.isNullOrBlank()) add("Coverage report with no branches needs an explicit explanation")
            } else if (branches < minimumBranch) add("Branch coverage $branches% is below $minimumBranch%")
        }
    }

    private fun percentage(covered: Long, missed: Long): Double? {
        val total = covered.toDouble() + missed.toDouble()
        return if (total == 0.0) null else covered.toDouble() * 100.0 / total
    }
}

/** Outcomes recorded by the caller for a criterion or test run. */
@Serializable
enum class ValidationOutcome { PASSED, FAILED, NOT_RUN }

/** Criterion text and concrete observations, optionally accompanied by its TDD cycle. */
@Serializable
data class ValidationCheck(
    val criterion: String,
    val outcome: ValidationOutcome,
    val evidence: String,
    val tdd: TddEvidence? = null,
)

/** Validation is bound to the current requirement, specification, task, and guidance. */
@Serializable
data class ValidationRecord(
    val schema: String = "bosca.workops.validation.v1",
    val requirementId: String,
    val taskId: String,
    val taskVersion: Long,
    val specContentHash: String,
    val requirementContentHash: String,
    val checks: List<ValidationCheck>,
    val interfaceReviewId: Long? = null,
    val testQuality: TestQualityEvidence? = null,
    val guidanceRevision: String? = null,
)

/** Portable baseline of the plan and interfaces reviewed by the caller. */
@Serializable
data class SpecReviewRecord(
    val schema: String = "bosca.workops.review.v1",
    val specId: String,
    val note: String?,
    val snapshot: SpecReviewSnapshot,
    val interfaces: List<ReviewedInterface> = emptyList(),
)

/** Content and scope captured during review; workflow statuses are checked independently. */
@Serializable
data class SpecReviewSnapshot(
    val projectId: String?,
    val document: ReviewDocument,
    val requirements: List<ReviewRequirement>,
    val programId: String?,
    val parentSpecId: String?,
    val gitRepositoryId: String?,
    val gitPath: String?,
    val contexts: List<ReviewContext>,
    val guidanceRevision: String? = null,
)

/** A reference attached to the reviewed specification. */
@Serializable
data class ReviewContext(val type: String, val targetId: String, val label: String?, val attributes: JsonElement?)

/** Exact rich document content, including structural changes invisible in plain text. */
@Serializable
data class ReviewDocument(val title: String, val content: JsonElement)

/** Requirement identity, body, and criteria captured in a review. */
@Serializable
data class ReviewRequirement(val id: String, val key: String, val document: ReviewDocument, val acceptanceCriteria: List<String>)

/** Hashes the title and JSON body; object-key ordering is irrelevant and array ordering is preserved. */
fun ReviewDocument.fingerprint(): String {
    fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
    return MessageDigest.getInstance("SHA-256").digest((title + "\n" + canonical(content)).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
