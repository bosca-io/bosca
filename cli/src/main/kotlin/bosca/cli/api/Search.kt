package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.Search as SearchOp
import bosca.graphql.gen.SearchData

class Search(network: NetworkClient) : Api(network) {

    suspend fun search(query: String, filter: List<String>?, storageSystemId: String?): List<SearchData.Search.Search.Documents> =
        network.boscaGraphql.execute(SearchOp, SearchOp.Variables(query, filter, storageSystemId))
            .search.search?.documents ?: emptyList()
}
