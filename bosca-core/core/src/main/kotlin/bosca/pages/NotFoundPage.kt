package bosca.pages

import bosca.server.ServerCall

/**
 * Renders a custom 404 Not Found response for server-rendered pages.
 *
 * Implementations produce the HTTP response body (typically HTML) to display
 * when a requested page route cannot be found.
 */
interface NotFoundPage {

    /**
     * Handles the not-found response by writing an appropriate body to the [call].
     *
     * @param call the server call to respond to
     */
    suspend fun execute(call: ServerCall)
}
