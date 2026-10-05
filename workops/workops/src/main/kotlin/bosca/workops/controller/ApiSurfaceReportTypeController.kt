package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.workops.model.artifact.ApiSurfaceReport

// ── Marker objects ────────────────────────────────────────────────────

@TypeController(type = "WorkOpsApiSurfaceReport")
class ApiSurfaceReportTypeController : GraphQLController<ApiSurfaceReport> {
    @Field fun id(r: ApiSurfaceReport) = r.id
    @Field fun projectId(r: ApiSurfaceReport) = r.projectId
    @Field fun versionId(r: ApiSurfaceReport) = r.versionId
    @Field fun previousVersionId(r: ApiSurfaceReport) = r.previousVersionId
    @Field fun artifactPublicationId(r: ApiSurfaceReport) = r.artifactPublicationId
    @Field fun breakingChangeLevel(r: ApiSurfaceReport) = r.breakingChangeLevel
    @Field fun changes(r: ApiSurfaceReport) = r.changes
    @Field fun analyzedAt(r: ApiSurfaceReport) = r.analyzedAt
    @Field fun analyzerTool(r: ApiSurfaceReport) = r.analyzerTool
    @Field fun reportUrl(r: ApiSurfaceReport) = r.reportUrl
    @Field fun version(r: ApiSurfaceReport) = r.version
}
