package bosca.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {

    @Schema("attributesfilter.graphqls")
    val attributes: String

    @Schema("categories.graphqls")
    val categories: String

    @Schema("collections.graphqls")
    val collections: String

    @Schema("content.graphqls")
    val content: String

    @Schema("guideprogress.graphqls")
    val guideProgress: String

    // Shared CommentStatus enum — always loaded so Work Ops comment types and the
    // optional comments module can both reference it regardless of feature flags.
    @Schema("comment-status.graphqls")
    val commentStatus: String

    @Schema("sources.graphqls")
    val sources: String

    @Schema("templateattributes.graphqls")
    val templateattributes: String

    @Schema("urls.graphqls")
    val urls: String

    @Schema("metadata/bible.graphqls")
    val bible: String

    @Schema("metadata/documents.graphqls")
    val documents: String

    @Schema("metadata/guides.graphqls")
    val guides: String

    @Schema("metadata/data.graphqls")
    val data: String

    @Schema("metadata/metadata.graphqls")
    val metadata: String

    @Schema("metadata/supplementary.graphqls")
    val supplementary: String

    @Schema("metadata/media.graphqls")
    val media: String

    @Schema("traits.graphqls")
    val traits: String

    @Schema("states.graphqls")
    val states: String

    @Schema("transitions.graphqls")
    val transitions: String

    @Schema("templateattributetools.graphqls")
    val templateAttributeTools: String

    @Schema("timeevent/time_events.graphqls")
    val timeEvents: String

    @Schema("healthcheck.graphqls")
    val healthCheck: String

    @Schema("jobs.graphqls")
    val jobs: String
}
