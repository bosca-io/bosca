package bosca.installer.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.installer.model.PackageInstallation

@TypeController
class PackageInstallationController : GraphQLController<PackageInstallation> {

    @Field
    fun key(source: PackageInstallation): String = source.key

    @Field
    fun name(source: PackageInstallation): String = source.name

    @Field
    fun versions(source: PackageInstallation): List<PackageInstallationVersionContext> {
        return source.versions.map {
            PackageInstallationVersionContext(source, it)
        }
    }
}
