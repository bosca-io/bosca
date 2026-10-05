package bosca.pages

import bosca.server.ServerCall

/**
 * Renders a custom error response for server-rendered pages.
 *
 * Implementations produce the HTTP response body (typically HTML) to display
 * when an unhandled exception occurs while processing a page request.
 */
interface ErrorPage {

    /**
     * Handles the error response by writing an appropriate body to the [call].
     *
     * @param call the server call to respond to
     */
    suspend fun execute(call: ServerCall)
}
