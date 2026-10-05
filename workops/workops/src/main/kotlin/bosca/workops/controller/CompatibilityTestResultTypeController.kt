package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.workops.model.compatibility.CompatibilityTestResult

// ── Marker objects ────────────────────────────────────────────────────

@TypeController(type = "WorkOpsCompatibilityTestResult")
class CompatibilityTestResultTypeController : GraphQLController<CompatibilityTestResult> {
    @Field fun id(c: CompatibilityTestResult) = c.id
    @Field fun consumerProjectId(c: CompatibilityTestResult) = c.consumerProjectId
    @Field fun consumerVersionId(c: CompatibilityTestResult) = c.consumerVersionId
    @Field fun providerProjectId(c: CompatibilityTestResult) = c.providerProjectId
    @Field fun providerVersionId(c: CompatibilityTestResult) = c.providerVersionId
    @Field fun testSuite(c: CompatibilityTestResult) = c.testSuite
    @Field fun status(c: CompatibilityTestResult) = c.status
    @Field fun breakingChangesDetected(c: CompatibilityTestResult) = c.breakingChangesDetected
    @Field fun pipelineRunId(c: CompatibilityTestResult) = c.pipelineRunId
    @Field fun resultUrl(c: CompatibilityTestResult) = c.resultUrl
    @Field fun testedAt(c: CompatibilityTestResult) = c.testedAt
    @Field fun version(c: CompatibilityTestResult) = c.version
}
