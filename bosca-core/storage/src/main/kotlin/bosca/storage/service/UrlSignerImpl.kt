package bosca.storage.service

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class UrlSignerImpl(
    private val secretKey: String
) : UrlSigner {

    override fun sign(url: String, durationSecs: Long): String {
        val uri = URI.create(url)

        // Get the current UNIX timestamp
        val currentTime = Instant.now().epochSecond
        // Calculate expiration time
        val expiration = currentTime + durationSecs

        val path = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")

        // Include the `expires` parameter
        val unsignedPath = if (path.contains('?')) {
            "$path&expires=$expiration"
        } else {
            "$path?expires=$expiration"
        }

        // Create HMAC-SHA256 signer
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(secretKey.toByteArray(), "HmacSHA256")
        mac.init(secretKeySpec)

        // Generate cryptographic signature
        val signature = mac.doFinal(unsignedPath.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val unsignedUrl = if (path.contains('?')) {
            "$url&expires=$expiration"
        } else {
            "$url?expires=$expiration"
        }

        // Append signature to the URL
        return "$unsignedUrl&signature=$signature"
    }

    override fun verify(url: URI): Boolean {
        val queryParams: List<Pair<String, String>> = url.query?.let { queryString ->
            val queryParts = queryString.split("&")
            val parts = mutableListOf<Pair<String, String>>()
            for (part in queryParts) {
                val kv = part.split("=")
                if (kv.size == 2) {
                    parts.add(kv[0] to kv[1])
                } else if (kv.size == 1) {
                    parts.add(kv[0] to "")
                }
            }
            parts
        } ?: emptyList()

        var expires: Long? = null
        var signature: String? = null

        val unsignedUrl = StringBuilder(url.path ?: "")
        var first = true

        // Extract and rebuild the URL query string (excluding the signature parameter)
        for ((key, value) in queryParams) {
            if (key == "signature") {
                signature = value
                continue
            }
            if (key == "expires") {
                expires = value.toLongOrNull() ?: throw IllegalArgumentException("Invalid expires value")
            }
            if (first) {
                unsignedUrl.append('?')
                first = false
            } else {
                unsignedUrl.append('&')
            }
            unsignedUrl.append("$key=$value")
        }

        // Check that the URL has an expiration time
        val expirationTime = expires ?: return false // No expiration time found

        // Check if the URL has expired
        val currentTime = System.currentTimeMillis() / 1000
        if (expirationTime < currentTime) {
            return false // URL is expired
        }

        // Recreate the signature
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secretKey.toByteArray(), "HmacSHA256"))
        }
        mac.update(unsignedUrl.toString().toByteArray())
        val calculatedSignature = mac.doFinal().joinToString("") { "%02x".format(it) }

        // Compare the provided signature with the calculated signature using constant-time comparison
        return signature?.let {
            MessageDigest.isEqual(it.toByteArray(), calculatedSignature.toByteArray())
        } ?: false // No signature provided
    }
}