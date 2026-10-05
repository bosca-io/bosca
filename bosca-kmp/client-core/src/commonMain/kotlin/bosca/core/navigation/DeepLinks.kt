package bosca.core.navigation

/**
 * Listener that receives and handles deep link URLs dispatched by the [DeepLinks] singleton.
 *
 * Register an implementation on [DeepLinks.listener] to begin receiving deep link events.
 * Any deep links that arrive before a listener is registered are buffered and
 * delivered once the listener is set.
 */
interface DeepLinkListener {

    /**
     * Called when a deep link URL is received by the application.
     *
     * @param url the deep link URL to handle
     * @return `true` if the deep link was successfully handled, `false` otherwise
     */
    fun onDeepLink(url: String): Boolean
}

object DeepLinks {

    private var _listener: DeepLinkListener? = null
    private var pendingDeepLink: String? = null

    var listener: DeepLinkListener
        get() = _listener ?: error("DeepLinkListener not set")
        set(value) {
            _listener = value
            pendingDeepLink?.let {
                onDeepLink(it)
                pendingDeepLink = null
            }
        }

    fun onDeepLink(url: String): Boolean {
        println("DeepLink: $url")
        if (_listener == null) {
            println("DeepLink listener not set, saving deep link")
            pendingDeepLink = url
            return false
        } else {
            val result = listener.onDeepLink(url)
            println("DeepLink listener handled: $result")
            return result
        }
    }
}