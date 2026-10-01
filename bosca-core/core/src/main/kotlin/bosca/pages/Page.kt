package bosca.pages

import bosca.cache.RequestCache
import bosca.cache.asCoroutineContext
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.di.provide
import bosca.security.service.AuthenticationContext
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import gg.jte.Content
import gg.jte.output.WriterOutput
import gg.jte.support.LocalizationSupport
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter

abstract class Page {

    protected open val useContext
        get() = defaultUseContext

    suspend fun execute(call: ServerCall) {
        val connectionPool = provide<ConnectionPool>()
        val cache = RequestCache(provide(), provide())
        val connection = connectionPool.connection()
        // The model renders synchronously after the connection is released, so the localized
        // strings are loaded here, while a connection is still in scope, and rendered against below.
        var localization: LocalizationSupport? = null
        val model = try {
            withContext(connection.asCoroutineContext() + cache.asCoroutineContext()) {
                val model = execute(call, AuthenticationContext(call.authenticationContext, provide()))
                if (model != null && useContext) {
                    localization = provide<LocalizationSource>().get(languageTag(call))
                }
                model
            }
        } finally {
            connection.release()
        }
        if (model != null) {
            val bytes = ByteArrayOutputStream().use { baos ->
                OutputStreamWriter(baos).use { writer ->
                    val resolved = localization
                    if (resolved != null) {
                        call.withContext(resolved) {
                            model.writeTo(WriterOutput(writer))
                        }
                    } else {
                        model.writeTo(WriterOutput(writer))
                    }
                }
                baos.toByteArray()
            }
            call.respondBytes(bytes, ContentType.Text.Html, HttpStatusCode.OK)
        }
    }

    open val title: String? = null

    protected open fun setPageDetails(call: ServerCall) {
        val details = PageDetails(siteName = "site.name", title ?: "site.name")
        PageContext.setPageDetails(details)
    }

    private fun languageTag(call: ServerCall): String =
        call.request.queryParameters["language"] ?: call.request.acceptLanguage() ?: "en-US"

    protected open fun <T> ServerCall.withContext(localization: LocalizationSupport, block: () -> T): T {
        return PageContext.run {
            PageContext.setLocalization(localization)
            setPageDetails(this@withContext)
            block()
        }
    }

    protected abstract suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Content?

    companion object {

        var defaultUseContext = true
    }
}
