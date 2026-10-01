package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.workops.model.dependency.BuildBlocker

// ── Marker objects ────────────────────────────────────────────────────

@TypeController(type = "WorkOpsBuildBlocker")
class BuildBlockerTypeController : GraphQLController<BuildBlocker> {
    @Field fun blockerType(b: BuildBlocker) = b.blockerType
    @Field fun providerProjectId(b: BuildBlocker) = b.providerProjectId
    @Field fun providerVersionId(b: BuildBlocker) = b.providerVersionId
    @Field fun description(b: BuildBlocker) = b.description
    @Field fun apiSurfaceReportId(b: BuildBlocker) = b.apiSurfaceReportId
    @Field fun compatibilityTestId(b: BuildBlocker) = b.compatibilityTestId
}
