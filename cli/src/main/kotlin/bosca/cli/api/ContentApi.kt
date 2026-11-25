package bosca.cli.api

/**
 * Unified facade for content management operations, wrapping
 * [ContentMetadata] and [ContentCollections] into a single entry
 * point for MCP tools and CLI commands.
 */
class ContentApi(network: NetworkClient) {
    val images = ContentImages(network)
    val metadata = ContentMetadata(network)
    val collections = ContentCollections(network)
    val scripture = ContentScripture(network)
}
