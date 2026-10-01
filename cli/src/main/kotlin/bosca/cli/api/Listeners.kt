package bosca.cli.api

import bosca.graphql.client.subscribe
import bosca.graphql.gen.OnCollectionChanged
import bosca.graphql.gen.OnCollectionChangedData
import bosca.graphql.gen.OnMetadataChanged
import bosca.graphql.gen.OnMetadataChangedData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class Listeners(network: NetworkClient) : Api(network) {

    fun onMetadataChanged(): Flow<OnMetadataChangedData.Metadata> =
        network.boscaWs.subscribe(OnMetadataChanged, Unit).map { it.metadata }

    fun onCollectionChanged(): Flow<OnCollectionChangedData.Collection> =
        network.boscaWs.subscribe(OnCollectionChanged, Unit).map { it.collection }
}
