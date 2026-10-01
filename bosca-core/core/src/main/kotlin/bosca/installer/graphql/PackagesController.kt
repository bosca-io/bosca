package bosca.installer.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationHistory
import bosca.installer.model.PackageInstallations
import bosca.installer.service.PackageInstallationService
import bosca.security.service.AuthenticationContext

@TypeController
class PackagesController(
    private val service: PackageInstallationService
) : GraphQLController<Packages> {

    @Field
    suspend fun all(authenticationContext: AuthenticationContext): List<PackageInstallation> {
        if (authenticationContext.principal()?.hasGroup("sa")?.takeIf { it } == null) {
            authenticationContext.principal()?.hasGroup("administrators")?.takeIf { it } ?: return emptyList()
        }
        return PackageInstallations.get().installations
    }

    @Field
    suspend fun history(authenticationContext: AuthenticationContext): List<PackageInstallationHistory> {
        if (authenticationContext.principal()?.hasGroup("sa")?.takeIf { it } == null) {
            authenticationContext.principal()?.hasGroup("administrators")?.takeIf { it } ?: return emptyList()
        }
        return service.getHistory()
    }
}
