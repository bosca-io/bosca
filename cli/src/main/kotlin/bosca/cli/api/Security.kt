package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.AddGroup
import bosca.graphql.gen.AddPrincipalGroup
import bosca.graphql.gen.GetGroups
import bosca.graphql.gen.GetPermissionActions
import bosca.graphql.gen.IGroup

/**
 * Administrative security operations (groups, principal-group membership,
 * permission actions).
 *
 * Authentication (login / token refresh / sign-out) no longer lives here — it
 * moved to the shared `io.bosca:auth-shared` library, wired through [CliAuth]
 * and [NetworkClient.tokenProvider]. This class retains only the admin calls.
 */
class Security(network: NetworkClient) : Api(network) {

    suspend fun addPrincipalGroup(principalId: kotlin.uuid.Uuid, groupId: kotlin.uuid.Uuid) {
        network.boscaGraphql.execute(AddPrincipalGroup, AddPrincipalGroup.Variables(groupId, principalId))
    }

    suspend fun addGroup(name: String, description: String) {
        network.boscaGraphql.execute(AddGroup, AddGroup.Variables(name, description))
    }

    suspend fun getGroups(offset: Long, limit: Int): List<IGroup> =
        network.boscaGraphql.execute(GetGroups, GetGroups.Variables(offset, limit)).security.groups.all

    suspend fun getPermissionActions(): List<String> =
        network.boscaGraphql.execute(GetPermissionActions, Unit).security.actions.map { it.uppercase() }
}
