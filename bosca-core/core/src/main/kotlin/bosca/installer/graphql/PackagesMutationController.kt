package bosca.installer.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.installer.service.PackageInstallationService
import bosca.security.service.AuthenticationContext

@TypeController
class PackagesMutationController(
    private val service: PackageInstallationService
) : GraphQLController<PackagesMutation> {

    @Field
    suspend fun install(
        authenticationContext: AuthenticationContext,
        key: String,
        version: String
    ): Boolean {
        verifySaOrAdmin(authenticationContext)
        return service.install(key, version)
    }

    @Field
    suspend fun installInstaller(
        authenticationContext: AuthenticationContext,
        key: String,
        version: String,
        installerName: String,
        installerVersion: String
    ): Boolean {
        verifySaOrAdmin(authenticationContext)
        return service.install(key, version, installerName, installerVersion)
    }

    private fun verifySaOrAdmin(authenticationContext: AuthenticationContext) {
        val principal = authenticationContext.principal()
            ?: throw SecurityException("Unauthorized: authentication required")
        if (!principal.hasGroup("sa") && !principal.hasGroup("administrators")) {
            throw SecurityException("Unauthorized: requires sa or administrators group")
        }
    }
}
