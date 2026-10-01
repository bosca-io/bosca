package bosca.server

import kotlinx.coroutines.CancellationException

/**
 * Ends a streaming response at a time limit: its own limit (see [ServerResponse.respondStreaming]),
 * or a client that stopped reading it (see [StreamingResponse.flush]).
 *
 * Both are expected ends, for long-lived streams such as watch feeds that clients simply reconnect
 * to, and for clients that went quiet, so the request dispatcher does not report them as failures.
 * Any other cancellation that escapes a committed response is still reported.
 */
internal class StreamingTimeLimitException(message: String) : CancellationException(message)
