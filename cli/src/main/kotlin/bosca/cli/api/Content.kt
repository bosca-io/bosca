package bosca.cli.api

class Content(network: NetworkClient) : Api(network) {

    val collections = ContentCollections(network)
}