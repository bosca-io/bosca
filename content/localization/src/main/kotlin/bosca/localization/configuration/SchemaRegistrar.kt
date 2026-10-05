package bosca.localization.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {

    @Schema("localization/projects.graphqls")
    val projects: String

    @Schema("localization/strings.graphqls")
    val strings: String

    @Schema("localization/documents.graphqls")
    val documents: String

    @Schema("localization/history.graphqls")
    val history: String

    @Schema("localization/sync.graphqls")
    val sync: String

    @Schema("localization/mutations.graphqls")
    val mutations: String
}
