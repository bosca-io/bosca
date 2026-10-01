package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.workops.model.release.ReleaseNotes
import bosca.workops.model.release.VersionReleaseNotes
import bosca.workops.service.VersionService

// ── Marker objects ────────────────────────────────────────────────────

@TypeController(type = "WorkOpsReleaseNotes")
class ReleaseNotesTypeController : GraphQLController<ReleaseNotes> {
    @Field fun id(n: ReleaseNotes) = n.id
    @Field fun releaseId(n: ReleaseNotes) = n.releaseId
    @Field fun generatedAt(n: ReleaseNotes) = n.generatedAt
    @Field fun manuallyEdited(n: ReleaseNotes) = n.manuallyEdited
    @Field fun sections(n: ReleaseNotes) = n.sections
    @Field fun version(n: ReleaseNotes) = n.version
}

@TypeController(type = "WorkOpsVersionReleaseNotes")
class VersionReleaseNotesTypeController(
    private val versionService: VersionService,
) : GraphQLController<VersionReleaseNotes> {
    @Field fun id(n: VersionReleaseNotes) = n.id
    @Field fun versionId(n: VersionReleaseNotes) = n.versionId
    @Field fun sourceLocale(n: VersionReleaseNotes) = n.sourceLocale
    @Field fun generatedAt(n: VersionReleaseNotes) = n.generatedAt
    @Field fun manuallyEdited(n: VersionReleaseNotes) = n.manuallyEdited
    @Field fun variants(n: VersionReleaseNotes) = n.variants
    @Field suspend fun workOpsVersion(n: VersionReleaseNotes) = versionService.getById(n.versionId)
        ?: error("Version ${n.versionId} not found for release notes")
}
