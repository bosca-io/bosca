package bosca.pages.annotations

enum class PageAuthentication {
    NONE,
    REQUIRED,
    OPTIONAL
}

enum class PageMethod {
    GET,
    POST
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class PageController(
    val path: String,
    val method: PageMethod = PageMethod.GET,
    val authentication: PageAuthentication = PageAuthentication.OPTIONAL
)