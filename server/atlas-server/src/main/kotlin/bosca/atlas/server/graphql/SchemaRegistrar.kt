package bosca.atlas.server.graphql

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {
    /** Bible provider administration and source registration owned by Content. */
    @Schema("metadata/bible-providers.graphqls") val bibleProviders: String

    /** Federation administration owned by Collaboration. */
    @Schema("federation.graphqls") val federation: String

    /** Composition roots and namespaces for the standalone Git API. */
    @Schema("atlas-server.graphqls") val root: String

    /** Profile and search fields required to choose Git repository owners. */
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
