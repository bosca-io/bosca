package bosca.analytics.model

import bosca.server.Headers

class EventPipelineContext(
    private val headers: Headers
) {

    fun getHeaderValue(name: String): String? = headers[name]
}