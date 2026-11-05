package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.*
import kotlin.uuid.Uuid

/** Scripture discovery and use-checked retrieval through the caller's existing GraphQL transport. */
class ContentScripture(network: NetworkClient) : Api(network) {
    suspend fun list(offset: Int, limit: Int) = network.boscaGraphql.execute(
        ListScriptureBibles, ListScriptureBibles.Variables(offset, limit),
    )

    suspend fun books(id: Uuid, version: Int?, variant: String?) = network.boscaGraphql.execute(
        GetScriptureBooks, GetScriptureBooks.Variables(id, version, variant),
    )

    suspend fun references(id: Uuid, version: Int?, variant: String?, reference: String) = network.boscaGraphql.execute(
        ResolveScriptureReferences, ResolveScriptureReferences.Variables(id, version, variant, reference),
    )

    suspend fun verses(id: Uuid, version: Int?, variant: String?, reference: String) = network.boscaGraphql.execute(
        GetScriptureVerses, GetScriptureVerses.Variables(id, version, variant, reference),
    )
}
