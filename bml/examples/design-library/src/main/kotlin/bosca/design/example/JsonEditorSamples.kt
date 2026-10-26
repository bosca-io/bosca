package bosca.design.example

import kotlinx.serialization.json.*

/** Malformed source text and structured table data for editor examples. */
object JsonEditorSamples {
    const val invalid = "{invalid: "
    val table = buildJsonArray {
        addJsonObject { put("name", "Alpha"); put("count", 1) }
        addJsonObject { put("name", "Beta"); put("count", 2) }
    }
}
