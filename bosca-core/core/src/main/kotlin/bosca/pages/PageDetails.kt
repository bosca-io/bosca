package bosca.pages

data class PageDetails(
    val siteName: String = "site.name",
    val title: String,
    val description: String? = null,
    val logo: Logo? = null
)

data class Logo(
    val url: String,
    val title: String?
)