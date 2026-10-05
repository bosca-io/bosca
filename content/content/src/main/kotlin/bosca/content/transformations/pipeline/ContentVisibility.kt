package bosca.content.transformations.pipeline

import bosca.serialization.JsonConverter.toAny
import com.dashjoin.jsonata.Jsonata
import kotlinx.serialization.json.JsonElement

/**
 * The shared, reflection-free visibility rules used by both the **Build Search Document** nodes and
 * the standalone **Visibility Gate** node — so "what is allowed into the index" (or past a gate) is
 * decided in exactly one place, in typed Kotlin against the entity's own properties (never fragile
 * serialized field names).
 */
object ContentVisibility {

    /**
     * Whether an entity passes the configurable visibility constraints — each is enforced only when its
     * toggle is on, so a caller can choose to allow unpublished or non-public content (e.g. an admin
     * index branch turns the public/published requirements off).
     */
    fun passes(
        public: Boolean,
        published: Boolean,
        searchable: Boolean,
        deleted: Boolean,
        requirePublic: Boolean,
        requirePublished: Boolean,
        requireSearchable: Boolean,
        excludeDeleted: Boolean,
    ): Boolean =
        (!requirePublic || public) &&
            (!requirePublished || published) &&
            (!requireSearchable || searchable) &&
            (!excludeDeleted || !deleted)

    /**
     * Whether [json] (the entity's serialized form) satisfies an optional author-written [predicate]
     * JSONata — the "other constraints" knob (e.g. `attributes.indexable = true`). A blank predicate
     * always passes; otherwise the JSONata result is coerced to a boolean via the dashjoin bridge (no
     * reflective serializer lookup).
     */
    fun matches(predicate: String, json: JsonElement): Boolean {
        if (predicate.isBlank()) return true
        return Jsonata.boolize(Jsonata.jsonata(predicate).evaluate(json.toAny()))
    }
}
