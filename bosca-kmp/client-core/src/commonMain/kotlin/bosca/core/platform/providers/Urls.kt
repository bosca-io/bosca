package bosca.core.platform.providers

data class Urls(
    val web: String,
    val graphql: String,
    val graphqlWs: String,
    val images: String,
    val api: String,
    val analytics: String = api,
)
