package bosca.analytics.delivery

/** Collector request headers that identify the KMP installation and application build. */
object BoscaRequestHeaders {
    const val INSTALLATION_ID = "X-Installation-ID"
    const val APP_ID = "X-App-ID"
    const val APP_VERSION = "X-App-Version"
    const val SESSION_ID = "X-BA-Session-ID"
}
