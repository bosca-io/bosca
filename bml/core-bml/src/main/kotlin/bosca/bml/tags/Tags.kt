package bosca.bml.tags

/**
 * Bosca owns the entire tag vocabulary. This file defines the tag model and the
 * resolution contract shared by the compiler and the renderer.
 * See `docs/grammar.md` §7.
 */

/** How a resolved tag is backed. */
enum class TagCategory { Html, Special, Custom }

/** The built-in tag vocabulary: special (protected) tags, the HTML element set, and void elements. */
object BuiltinTags {
    /** Structural/control tags BML owns. Always win during resolution and CANNOT be overridden. */
    val special: Set<String> = setOf(
        "page", "route", "template", "component", "prop", "slot", "use",
        "for", "if", "else-if", "else", "data", "inject", "island", "fallback",
        "script", "contract", "style",
        "email", "subject",
    )

    /** HTML void elements (no children, no closing tag). */
    val void: Set<String> = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr",
    )

    /** Complete modern SVG element vocabulary (deprecated font/animation aliases intentionally included). */
    val svg: Set<String> = setOf(
        "a", "animate", "animateMotion", "animateTransform", "circle", "clipPath", "defs", "desc",
        "discard", "ellipse", "feBlend", "feColorMatrix", "feComponentTransfer", "feComposite",
        "feConvolveMatrix", "feDiffuseLighting", "feDisplacementMap", "feDistantLight", "feDropShadow",
        "feFlood", "feFuncA", "feFuncB", "feFuncG", "feFuncR", "feGaussianBlur", "feImage", "feMerge",
        "feMergeNode", "feMorphology", "feOffset", "fePointLight", "feSpecularLighting", "feSpotLight",
        "feTile", "feTurbulence", "filter", "foreignObject", "g", "image", "line", "linearGradient",
        "marker", "mask", "metadata", "mpath", "path", "pattern", "polygon", "polyline", "radialGradient",
        "rect", "script", "set", "stop", "style", "svg", "switch", "symbol", "text", "textPath", "title",
        "tspan", "use", "view",
        // SVG 1.1/deprecated elements still found in authored and generated artwork.
        "altGlyph", "altGlyphDef", "altGlyphItem", "animateColor", "color-profile", "cursor", "font",
        "font-face", "font-face-format", "font-face-name", "font-face-src", "font-face-uri", "glyph",
        "glyphRef", "hkern", "missing-glyph", "tref", "vkern",
    )

    /**
     * The built-in HTML element set. Names that BML reuses as special tags
     * (`data`, `slot`, `template`, `script`, `style`) are intentionally excluded —
     * BML owns them; reach the native element via the `html:` namespace if ever needed.
     */
    val html: Set<String> = setOf(
        "a", "abbr", "address", "area", "article", "aside", "audio", "b", "base", "bdi", "bdo",
        "blockquote", "body", "br", "button", "canvas", "caption", "cite", "code", "col", "colgroup",
        "datalist", "dd", "del", "details", "dfn", "dialog", "div", "dl", "dt", "em", "embed",
        "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6",
        "head", "header", "hgroup", "hr", "html", "i", "iframe", "img", "input", "ins", "kbd", "label",
        "legend", "li", "link", "main", "map", "mark", "menu", "meta", "meter", "nav", "noscript",
        "object", "ol", "optgroup", "option", "output", "p", "param", "picture", "pre", "progress", "q",
        "rp", "rt", "ruby", "s", "samp", "section", "select", "small", "source", "span", "strong", "sub",
        "summary", "sup", "svg", "table", "tbody", "td", "textarea", "tfoot", "th", "thead", "time",
        "title", "tr", "track", "u", "ul", "var", "video", "wbr",
        // Common unambiguous inline-SVG children; the complete vocabulary lives in [svg].
        "path", "circle", "rect", "line", "polyline", "polygon", "g", "defs",
    )

    fun isProtected(name: String): Boolean = name in special
    fun isVoid(name: String): Boolean = name in void
}

/** A typed input declared on a custom tag (from `<prop>`). */
data class TagProp(
    val name: String,
    val type: String?,
    val required: Boolean,
    val hasDefault: Boolean,
)

/** Scope in which a custom tag/override applies. v1 supports global registration only. */
enum class TagScope { Global }

/** A custom-tag (or override) declaration discovered from a `<component tag="…">`. */
data class CustomTag(
    val tag: String,
    val overrides: Boolean,        // true when it replaces a built-in HTML tag's default
    val props: List<TagProp> = emptyList(),
    val scope: TagScope = TagScope.Global,
)

/** The outcome of resolving an element name. */
sealed interface TagResolution {
    val category: TagCategory?

    data class Html(val name: String, val isVoid: Boolean) : TagResolution {
        override val category get() = TagCategory.Html
    }

    data class Special(val name: String) : TagResolution {
        override val category get() = TagCategory.Special
    }

    data class Custom(val decl: CustomTag) : TagResolution {
        override val category get() = TagCategory.Custom
    }

    data class Error(val message: String) : TagResolution {
        override val category: TagCategory? get() = null
    }
}

/** The outcome of registering a custom tag/override. */
sealed interface RegisterResult {
    data object Ok : RegisterResult
    data class Rejected(val reason: String) : RegisterResult
}

/**
 * Resolves element names against the built-ins and registered custom tags.
 *
 * Resolution order for an un-namespaced name:
 * 1. special (protected) — always wins;
 * 2. a registered custom tag/override;
 * 3. a built-in HTML or SVG element;
 * 4. otherwise an error.
 *
 * The `html:` and `svg:` namespaces bypass overrides and resolve straight to their native
 * handlers — the escape hatch an override uses to emit the tag it overrides.
 */
interface TagRegistry {
    fun register(tag: CustomTag): RegisterResult
    fun resolve(name: String, namespace: String? = null): TagResolution
}
