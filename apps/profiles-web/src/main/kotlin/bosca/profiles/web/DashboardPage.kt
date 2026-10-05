package bosca.profiles.web

data class DashboardPage(
    val name: String,
    val relationshipCount: Int,
    val deviceCount: Int,
    val verified: Boolean,
    val lastLogin: String,
)
