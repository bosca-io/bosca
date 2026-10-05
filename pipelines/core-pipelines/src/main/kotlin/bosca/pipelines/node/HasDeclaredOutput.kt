package bosca.pipelines.node

import bosca.pipelines.annotation.SlotKind
import kotlinx.serialization.json.JsonElement

/**
 * A node whose output kind/type is declared **per instance**, overriding the (often dynamic) output
 * of its node type. A generic transform like a JSONata node is `ANY` by type — it could return
 * anything — so a typed input slot refuses its output at connect time ([SlotConnectionValidator]). A
 * builder who knows the expression returns, say, a string declares it here, and both the connection
 * validator and the editor honor that instead of the type's dynamic descriptor.
 *
 *  - [declaredOutputKind] — the coarse [SlotKind] the instance produces; [SlotKind.ANY] means "not
 *    declared" (fall back to the node type's descriptor).
 *  - [declaredOutputType] — an optional specific object type (serial name) for a type-pinned object
 *    slot; `""` = none.
 *  - [declaredOutputSchema] — an optional JSON-Schema-subset (the [JsonSchemaValidator] subset) the
 *    output is validated against at run time, so a wrong shape fails the run instead of flowing on
 *    silently; `null` = none.
 *
 * Pure metadata read off the decoded node instance — no registry, no reflection.
 */
interface HasDeclaredOutput {
    val declaredOutputKind: SlotKind
    val declaredOutputType: String get() = ""
    val declaredOutputSchema: JsonElement? get() = null
}
