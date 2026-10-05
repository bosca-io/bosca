package bosca.documents.yjs

import bosca.documents.Content
import bosca.documents.DocumentSerializers
import bosca.serialization.UUIDSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.plus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.put
import yks.types.YType
import yks.types.YXmlElement
import yks.types.YXmlFragment
import yks.types.YXmlText
import yks.types.typeMapSet
import yks.utils.Doc
import yks.utils.applyUpdate
import yks.utils.encodeStateAsUpdate

/**
 * Bridges Bosca's ProseMirror-flavoured document JSON and the Yjs CRDT representation
 * used by the collaborative editor.
 *
 * The collaborative editor (`@tiptap/extension-collaboration` via `y-prosemirror`)
 * binds a TipTap document to a Yjs `XmlFragment` named `default` inside the shared
 * `Doc`. Each ProseMirror block becomes a `YXmlElement` whose tag matches the node
 * type (`paragraph`, `heading`, `bulletList`, …); each text node becomes a
 * `YXmlText` whose formatting attributes carry ProseMirror marks. Node attributes
 * become `YXmlElement` attributes — strings are stored verbatim and non-strings are
 * JSON-encoded so they can round-trip through the string-only API.
 *
 * This bridge lets server-side code (workflows, AI pipelines, programmatic edits)
 * keep the collaboration state coherent with the canonical `documents` row without
 * routing through a JS process.
 */
object ProseMirrorYjsBridge {

    /** Convention used by `@tiptap/extension-collaboration`: PM doc is bound to `getXmlFragment("default")`. */
    private const val DEFAULT_FRAGMENT = "default"

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        // Document nodes are polymorphic (sealed interface DocumentNode); the contextual
        // UUID serializer is required for nodes like ContainerNode that carry a UUID
        // attribute via @Contextual.
        serializersModule = DocumentSerializers + SerializersModule { contextual(UUIDSerializer()) }
    }

    /**
     * Serializes [content] into a Yjs binary update that, when applied to a fresh `Doc`,
     * yields a `default` fragment populated with the ProseMirror tree.
     */
    fun toYDocUpdate(content: Content): ByteArray = toYDocUpdate(toJsonElement(content))

    /** [toYDocUpdate] overload accepting an already-serialized JSON tree. */
    fun toYDocUpdate(docJson: JsonElement): ByteArray {
        val doc = Doc()
        val fragment = doc.getXmlFragment(DEFAULT_FRAGMENT)
        appendFragmentChildren(fragment, docJson)
        return encodeStateAsUpdate(doc)
    }

    /**
     * Decodes the Yjs binary [state] (typically a state-as-update blob from
     * `document_collaborations.content`) into a Bosca [Content] tree.
     *
     * Returns an empty [Content] if [state] is empty or the `default` fragment is empty.
     */
    fun toContent(state: ByteArray): Content = fromJsonElement(toProseMirrorJson(state))

    /** [toContent] but returning the raw JSON tree, useful for inspection or remixing. */
    fun toProseMirrorJson(state: ByteArray): JsonElement {
        val doc = Doc()
        if (state.isNotEmpty()) applyUpdate(doc, state)
        val fragment = doc.getXmlFragment(DEFAULT_FRAGMENT)
        val children = fragment.toArray().flatMap { yNodeToJson(it) }
        return buildJsonObject {
            put("type", "doc")
            put("content", JsonArray(children))
        }
    }

    /**
     * Merges [content] into [existingState] as a structural diff against the existing
     * `default` fragment. Subtrees that match the existing CRDT bit-for-bit are left as
     * untouched Yjs items, so concurrent in-flight editor edits inside those regions
     * survive the merge unchanged. Subtrees that differ are replaced via Yjs delete/insert
     * operations at the smallest position where the diff is detected. Non-`default` Yjs
     * maps (collections-dirty flags, awareness markers, attribute maps) are never touched.
     *
     * Why this matters: a wholesale `delete-everything-then-reinsert` would generate Yjs
     * deletions covering the entire fragment, which would then race against any unsaved
     * edits the editor has buffered locally — those edits would either get swept up in
     * the deletion or merged into orphan positions. Diff-based replacement bounds the
     * scope of the disturbance to the regions that actually changed.
     */
    fun mergeDocument(existingState: ByteArray, content: Content): ByteArray =
        mergeDocument(existingState, toJsonElement(content))

    /** [mergeDocument] overload accepting an already-serialized JSON tree. */
    fun mergeDocument(existingState: ByteArray, docJson: JsonElement): ByteArray {
        val doc = Doc()
        if (existingState.isNotEmpty()) applyUpdate(doc, existingState)
        val fragment = doc.getXmlFragment(DEFAULT_FRAGMENT)
        val newChildren = (docJson as? JsonObject)?.get("content") as? JsonArray ?: JsonArray(emptyList())
        diffMergeChildren(fragment, newChildren)
        return encodeStateAsUpdate(doc)
    }

    // ── PM JSON → Yjs ────────────────────────────────────────────────────────────

    private fun appendFragmentChildren(fragment: YXmlFragment, docJson: JsonElement) {
        val children = (docJson as? JsonObject)?.get("content") as? JsonArray ?: return
        children.forEach { insertChildAt(fragment, fragment.length, it) }
    }

    /**
     * Inserts the PM-JSON [json] as a new child of [parent] at [index]. Returns silently
     * for inputs that don't shape like a node (missing `type`, empty text). Caller is
     * responsible for choosing [index] within `0..parent.length`.
     */
    private fun insertChildAt(parent: YXmlFragment, index: Int, json: JsonElement) {
        val obj = json as? JsonObject ?: return
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
        if (type == "text") {
            val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (text.isEmpty()) return
            val xmlText = YXmlText()
            parent.insert(index, listOf(xmlText))
            val marks = formatAttrsFromMarks(obj["marks"] as? JsonArray)
            xmlText.insert(0, text, marks)
            return
        }
        val element = YXmlElement(type)
        parent.insert(index, listOf(element))
        applyAttributes(element, obj["attrs"] as? JsonObject)
        (obj["content"] as? JsonArray)?.forEach { child -> insertChildAt(element, element.length, child) }
    }

    /**
     * LCS-based structural diff between [parent]'s current children and [newChildren].
     * Computes the longest common subsequence (using [yNodeMatchesJson] as the equality
     * predicate), then issues only the minimal sequence of Yjs delete/insert ops needed
     * to convert old → new. Items in the LCS are left as untouched Yjs items — they keep
     * their original item IDs and clocks, so concurrent in-flight editor edits inside
     * those subtrees survive the merge.
     *
     * Concretely this means:
     * - removing a section from the middle deletes only that section, not everything after
     * - inserting a section in the middle inserts only that section, not also re-inserting
     *   everything after (which a naive position-by-position diff would do)
     * - reordering blocks is *not* detected as a move; it appears as delete+insert. Yjs
     *   doesn't have a native move op, and modeling it through the diff would lose the
     *   identity property that makes the merge useful.
     *
     * The diff is intentionally shallow at each level — when an old/new pair differs at
     * a sub-element, the entire subtree is replaced rather than recursively diffed.
     * Recursive diffing buys more preservation but with O(n^2) per level; revisit if a
     * workload needs it.
     *
     * Complexity: O(n*m) time / space for the LCS table where n,m are the child counts
     * at this level. Document trees rarely have more than a few dozen siblings, so this
     * is bounded in practice.
     */
    private fun diffMergeChildren(parent: YXmlFragment, newChildren: JsonArray) {
        val oldNodes = parent.toArray()
        val ops = computeEditScript(oldNodes, newChildren)
        var fragPos = 0
        for (op in ops) {
            when (op) {
                is EditOp.Keep -> fragPos++
                is EditOp.Delete -> parent.delete(fragPos, 1)
                is EditOp.Insert -> {
                    insertChildAt(parent, fragPos, newChildren[op.newIndex])
                    fragPos++
                }
            }
        }
    }

    /** A single edit-script step. [EditOp.Keep] advances the cursor past an unchanged child. */
    private sealed interface EditOp {
        data object Keep : EditOp
        data object Delete : EditOp
        data class Insert(val newIndex: Int) : EditOp
    }

    /**
     * Standard LCS-derived edit script. Returns the list of operations (in apply-order)
     * that converts [oldNodes] into [newNodes] using only KEEP / DELETE / INSERT.
     */
    private fun computeEditScript(oldNodes: List<YType>, newNodes: JsonArray): List<EditOp> {
        val n = oldNodes.size
        val m = newNodes.size
        if (n == 0) return List(m) { EditOp.Insert(it) }
        if (m == 0) return List(n) { EditOp.Delete }
        // lcs[i][j] = length of longest common subsequence of oldNodes[0..i) and newNodes[0..j).
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                lcs[i][j] = if (yNodeMatchesJson(oldNodes[i - 1], newNodes[j - 1])) {
                    lcs[i - 1][j - 1] + 1
                } else {
                    maxOf(lcs[i - 1][j], lcs[i][j - 1])
                }
            }
        }
        // Backtrack from (n,m) to (0,0), emitting ops in reverse.
        val reversed = mutableListOf<EditOp>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && yNodeMatchesJson(oldNodes[i - 1], newNodes[j - 1]) -> {
                    reversed.add(EditOp.Keep); i--; j--
                }
                j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j]) -> {
                    reversed.add(EditOp.Insert(j - 1)); j--
                }
                else -> {
                    reversed.add(EditOp.Delete); i--
                }
            }
        }
        reversed.reverse()
        return reversed
    }

    /**
     * Structural equality between a Yjs node and a PM-JSON node. The comparison goes all
     * the way down — only an exact match returns true, so the diff above can safely leave
     * the matched subtree untouched.
     */
    private fun yNodeMatchesJson(yNode: YType, json: JsonElement): Boolean {
        if (json !is JsonObject) return false
        val type = json["type"]?.jsonPrimitive?.contentOrNull ?: return false
        return when (yNode) {
            is YXmlText -> {
                if (type != "text") return false
                // Compare the text segments produced by toDelta against the JSON's
                // (text, marks) tuple. textNodesFromDelta is the canonical encoding,
                // so re-using it avoids drift between the two paths.
                val produced = textNodesFromDelta(yNode)
                if (produced.size != 1) {
                    // Multi-segment YXmlText would correspond to multiple PM text nodes;
                    // a single PM text node can only match a single-segment YXmlText.
                    return false
                }
                produced[0] == json
            }
            is YXmlElement -> {
                if (yNode.tag != type) return false
                if (elementToJson(yNode) != json) return false
                true
            }
            else -> false
        }
    }

    /**
     * Writes the PM node's attribute map into the YXmlElement using Yjs's typed-attribute
     * storage. y-prosemirror stores attrs as native typed values (numbers stay numbers,
     * booleans stay booleans, nested objects/arrays stay structured), so we go through
     * [typeMapSet] directly rather than [YXmlElement.setAttribute] — the latter is
     * String-typed and would round-trip `String "42"` as `Number 42`, breaking the
     * deserializer and (worse) silently changing field values.
     */
    private fun applyAttributes(element: YXmlElement, attrs: JsonObject?) {
        if (attrs == null) return
        val doc = element.doc ?: error("YXmlElement must be integrated into a Doc before attributes are applied")
        doc.transact { txn ->
            for ((key, value) in attrs) {
                val native = jsonToNative(value)
                if (native == null && value is JsonNull) continue
                typeMapSet(txn, element, key, native)
            }
        }
    }

    private fun formatAttrsFromMarks(marks: JsonArray?): Map<String, Any?>? {
        if (marks == null || marks.isEmpty()) return null
        val out = LinkedHashMap<String, Any?>()
        for (mark in marks) {
            val obj = mark as? JsonObject ?: continue
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: continue
            val attrs = obj["attrs"] as? JsonObject
            // y-prosemirror convention: simple boolean marks (bold, italic, …) are
            // stored as `{<type>: true}`; marks with attrs are stored as `{<type>: <attrs>}`
            // where the attrs value would ideally be a native map. yks's ContentFormat
            // currently coerces values via `toString()` (lossy for Maps), so we serialize
            // the attrs as a JSON string here and parse them back in [marksFromFormatAttrs].
            // Fixing yks to write `ContentFormat` values via `writeAny` would let us drop
            // the indirection — until then, the JSON-string form is the only shape that
            // round-trips through the existing ContentFormat wire format.
            out[type] = if (attrs == null || attrs.isEmpty()) true else attrs.toString()
        }
        return out
    }

    /**
     * Converts a [JsonElement] to a native Kotlin value suitable for Yjs's typed-attribute
     * storage. [JsonNull] becomes null; numbers prefer Long for integer values so the wire
     * format matches what `y-prosemirror` produces (Yjs's lib0 encodes integers as varints).
     */
    private fun jsonToNative(value: JsonElement): Any? = when (value) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.boolean
            value.longOrNull != null -> value.long
            value.doubleOrNull != null -> value.double
            else -> value.content
        }
        is JsonArray -> value.map { jsonToNative(it) }
        is JsonObject -> value.entries.associate { (k, v) -> k to jsonToNative(v) }
    }

    /** Inverse of [jsonToNative]. */
    @Suppress("UNCHECKED_CAST")
    private fun nativeToJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map { nativeToJson(it) })
        is Map<*, *> -> JsonObject((value as Map<Any?, Any?>).entries.associate { (k, v) -> k.toString() to nativeToJson(v) })
        else -> JsonPrimitive(value.toString())
    }

    // ── Yjs → PM JSON ────────────────────────────────────────────────────────────

    /**
     * Converts a single Yjs node into one or more PM JSON nodes. A [YXmlText] expands
     * to a list of text nodes, one per delta segment with distinct formatting; a
     * [YXmlElement] becomes a single block node. Returns a list so callers can
     * `flatMap` without having to special-case text.
     */
    private fun yNodeToJson(node: YType): List<JsonElement> = when (node) {
        is YXmlText -> textNodesFromDelta(node)
        is YXmlElement -> listOf(elementToJson(node))
        else -> emptyList()
    }

    private fun elementToJson(element: YXmlElement): JsonObject = buildJsonObject {
        put("type", element.tag)
        val attrs = element.getAttributes()
        if (attrs.isNotEmpty()) {
            put("attrs", buildJsonObject {
                attrs.forEach { (k, v) -> put(k, nativeToJson(v)) }
            })
        }
        val children = element.toArray().flatMap { yNodeToJson(it) }
        if (children.isNotEmpty()) put("content", JsonArray(children))
    }

    private fun textNodesFromDelta(text: YXmlText): List<JsonElement> {
        val deltas = text.toDelta()
        if (deltas.isEmpty()) return emptyList()
        return deltas.mapNotNull { op ->
            val insert = op["insert"] as? String ?: return@mapNotNull null
            val attrs = @Suppress("UNCHECKED_CAST") (op["attributes"] as? Map<String, Any?>)
            buildJsonObject {
                put("type", "text")
                put("text", insert)
                val marks = marksFromFormatAttrs(attrs)
                if (marks.isNotEmpty()) put("marks", JsonArray(marks))
            }
        }
    }

    private fun marksFromFormatAttrs(attrs: Map<String, Any?>?): List<JsonElement> {
        if (attrs.isNullOrEmpty()) return emptyList()
        return attrs.mapNotNull { (key, value) ->
            // A `false`/`null` value indicates the mark was explicitly removed.
            if (value == null) return@mapNotNull null
            if (value is Boolean && !value) return@mapNotNull null
            buildJsonObject {
                put("type", key)
                if (value !is Boolean) {
                    // See [formatAttrsFromMarks] — mark attrs round-trip as a JSON string
                    // because `ContentFormat` `toString()`s its value. Parse only if the
                    // string actually decodes to an object; otherwise drop, since a mark
                    // with malformed attrs is more likely a corrupt write than a real
                    // structural value worth preserving.
                    val str = value.toString()
                    val parsed = runCatching { json.parseToJsonElement(str) }.getOrNull()
                    if (parsed is JsonObject) put("attrs", parsed)
                }
            }
        }
    }

    // ── Content ↔ JsonElement ────────────────────────────────────────────────────

    private fun toJsonElement(content: Content): JsonElement =
        json.encodeToJsonElement(Content.serializer(), content).let { wrapped ->
            // Content serializes as `{"document": {...}}`; the bridge wants the raw doc.
            (wrapped as JsonObject)["document"] ?: buildJsonObject { put("type", "doc") }
        }

    private fun fromJsonElement(docJson: JsonElement): Content {
        val wrapped = buildJsonObject { put("document", docJson) }
        // Don't swallow SerializationException here — a failure means the collaboration row
        // contains a node shape we don't recognize, which is a real bug (model evolution out
        // of step with the editor, corrupted state, hostile input). Returning an empty Content
        // would silently destroy whatever the user was working on. Surface to the caller so the
        // failure is visible in logs and HTTP responses; callers that want a graceful fallback
        // can catch it explicitly.
        return json.decodeFromJsonElement(Content.serializer(), wrapped)
    }
}
