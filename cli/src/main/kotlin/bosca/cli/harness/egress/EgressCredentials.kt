package bosca.cli.harness.egress

import java.security.MessageDigest
import java.util.Base64

/** A runtime's egress identity: its chat session and the generation's egress token. */
internal data class EgressCredentials(val session: String, val token: String) {
    /** The `Proxy-Authorization` value carrying these credentials. */
    fun proxyAuthorization(): String = "Basic " + Base64.getEncoder().encodeToString("$session:$token".toByteArray(Charsets.UTF_8))

    /** A cache key that keeps the token itself out of long-lived memory structures. */
    fun cacheKey(): String = session + ":" + MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).toHexString()

    override fun toString(): String = "EgressCredentials(session=$session)"
}
