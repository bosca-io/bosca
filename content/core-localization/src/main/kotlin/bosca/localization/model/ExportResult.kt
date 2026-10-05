package bosca.localization.model

import kotlinx.serialization.Serializable

/**
 * The serialized output of an export operation, ready for the caller to write to disk
 * or stream to the browser.
 *
 * @property content the rendered export body (XML, JSON, plist, etc.)
 * @property contentType the MIME type the CLI or browser should use when saving
 * @property fileName a sensible default filename, typically
 *  `{language-tag}.{extension}` or a platform-specific path segment
 *  (e.g. `values-es/strings.xml`)
 */
@Serializable
data class ExportResult(
    val content: String,
    val contentType: String,
    val fileName: String
)
