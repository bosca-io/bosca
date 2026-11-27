package bosca.atlas.server.graphql

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {
    /** Shared content queries and template attribute contracts owned by Core. */
    @Schema("attributes.graphqls") val attributes: String
    @Schema("find.graphqls") val find: String
    @Schema("ordering.graphqls") val ordering: String

    /** Owning telemetry contracts reached by WorkOps release health and live-session maps. */
    @Schema("atlas-analytics.graphqls") val analytics: String
    @Schema("analytics/counters.graphqls") val counters: String
    @Schema("analytics/error-groups.graphqls") val errorGroups: String
    @Schema("analytics/live-sessions.graphqls") val liveSessions: String

    /** Profile identity and document progression contracts used by Content. */
    @Schema("profiles.graphqls") val profiles: String
    @Schema("organizations.graphqls") val organizations: String

    /** Federation administration owned by Collaboration. */
    @Schema("federation.graphqls") val federation: String

    /** Composition roots and namespaces for the shared Atlas API. */
    @Schema("atlas-server.graphqls") val root: String

    /** Search fields used by repository owners and WorkOps document references. */
    @Schema("atlas-identity.graphqls") val identity: String

    /** Studio persona administration owned by Profile. */
    @Schema("personas.graphqls") val personas: String

    /** Runtime configuration administration. */
    @Schema("configurations.graphqls") val configurations: String

    /** Package installation administration. */
    @Schema("packages.graphqls") val packages: String

    /** Storage systems and their model catalog. */
    @Schema("storagesystems.graphqls") val storageSystems: String
    @Schema("models.graphqls") val models: String

    /** System administration namespaces. */
    @Schema("atlas-system.graphqls") val system: String

    /** Shared scalar contracts supplied by Bosca Core. */
    @Schema("types.graphqls") val scalars: String
}
