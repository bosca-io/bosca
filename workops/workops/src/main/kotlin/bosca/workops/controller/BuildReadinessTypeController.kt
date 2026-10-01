package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.workops.model.dependency.BuildReadiness

// ── Marker objects ────────────────────────────────────────────────────

@TypeController(type = "WorkOpsBuildReadiness")
class BuildReadinessTypeController : GraphQLController<BuildReadiness> {
    @Field fun ready(b: BuildReadiness) = b.ready
    @Field fun blockers(b: BuildReadiness) = b.blockers
}
