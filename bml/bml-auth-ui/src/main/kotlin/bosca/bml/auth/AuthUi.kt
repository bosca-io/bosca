package bosca.bml.auth

/** Base theme and authentication styles shared by independently composed BML applications. */
object AuthUi {
    val css: String get() = requireNotNull(AuthUi::class.java.getResourceAsStream("/auth-ui.css")) {
        "Authentication UI stylesheet missing"
    }.use { it.readAllBytes().decodeToString() }
}
