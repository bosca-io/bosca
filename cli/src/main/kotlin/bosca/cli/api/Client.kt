package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.ClearCache
import bosca.graphql.gen.GetSlug
import bosca.graphql.gen.GetSlugData

data class SlugItem(
    val metadata: GetSlugData.Content.Slug.Metadata? = null,
    val collection: GetSlugData.Content.Slug.Collection? = null,
    val profile: GetSlugData.Content.Slug.Profile? = null
)

class Client(val network: NetworkClient) {

    val apiTokens = ApiTokens(network)
    val security = Security(network)
    val collections = ContentCollections(network)
    val metadata = ContentMetadata(network)
    val categories = ContentCategories(network)
    val comments = Comments(network)
    val configurations = Configurations(network)
    val workflows = Workflows(network)
    val search = Search(network)
    val profiles = Profiles(network)
    val listeners = Listeners(network)
    val runPod = RunPod(this)
    val files = Files(network)

    suspend fun get(slug: String): SlugItem? {
        val result = network.boscaGraphql.execute(GetSlug, GetSlug.Variables(slug)).content.slug ?: return null
        return SlugItem(
            metadata = result as? GetSlugData.Content.Slug.Metadata,
            collection = result as? GetSlugData.Content.Slug.Collection,
            profile = result as? GetSlugData.Content.Slug.Profile,
        )
    }

    suspend fun clearCache() {
        // Migrated off Apollo to the Bosca-native typed client (the first call site to ride boscaGraphql).
        network.boscaGraphql.execute(ClearCache, Unit)
    }
}
