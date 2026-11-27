package bosca.workops.ui

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Encodes a WorkOps path segment or query value without interpreting BQL syntax. */
fun workOpsEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
