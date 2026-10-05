package bosca.routes.annotations

enum class RouteAuthentication {
    NONE,
    REQUIRED,
    OPTIONAL
}

enum class RouteMethod {
    GET,
    HEAD,
    POST,
    PUT,
    DELETE,
    PATCH
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RouteController(
    val path: String,
    val method: RouteMethod = RouteMethod.GET,
    val authentication: RouteAuthentication = RouteAuthentication.OPTIONAL
)