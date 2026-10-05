package bosca.profiles.web

/** Deployment-configurable presentation shared with the notification preference site. */
data class Branding(
    val name: String,
    val logoUrl: String,
    val footerHtml: String,
    val primaryColor: String,
    val accentColor: String,
)
