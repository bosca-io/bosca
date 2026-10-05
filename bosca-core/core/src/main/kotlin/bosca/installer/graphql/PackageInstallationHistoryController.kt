package bosca.installer.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.installer.model.PackageInstallationHistory
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@TypeController(type = "PackageInstallationHistory")
class PackageInstallationHistoryController : GraphQLController<PackageInstallationHistory> {

    @Field
    fun id(source: PackageInstallationHistory): UUID = source.id

    @Field
    fun key(source: PackageInstallationHistory): String = source.key

    @Field
    fun version(source: PackageInstallationHistory): String = source.version

    @Field
    fun created(source: PackageInstallationHistory): OffsetDateTime = source.created
}
