package bosca.bml.tags

/**
 * The default [TagRegistry]: pure resolution logic with no infrastructure
 * dependencies, usable from both the compiler and the renderer.
 */
class DefaultTagRegistry : TagRegistry {

    private val custom = mutableMapOf<String, CustomTag>()

    override fun register(tag: CustomTag): RegisterResult {
        if (BuiltinTags.isProtected(tag.tag)) {
            return RegisterResult.Rejected("Cannot override protected tag '<${tag.tag}>'")
        }
        custom[tag.tag] = tag
        return RegisterResult.Ok
    }

    override fun resolve(name: String, namespace: String?): TagResolution {
        if (namespace == "html") {
            return if (name in BuiltinTags.html) {
                TagResolution.Html(name, BuiltinTags.isVoid(name))
            } else {
                TagResolution.Error("Unknown HTML element '<html:$name>'")
            }
        }
        if (namespace == "svg") {
            return if (name in BuiltinTags.svg) {
                TagResolution.Html(name, false)
            } else {
                TagResolution.Error("Unknown SVG element '<svg:$name>'")
            }
        }
        if (namespace != null) {
            return TagResolution.Error("Unknown tag namespace '$namespace:' (only 'html:' and 'svg:' are defined)")
        }
        if (name in BuiltinTags.special) return TagResolution.Special(name)
        custom[name]?.let { return TagResolution.Custom(it) }
        if (name in BuiltinTags.html || name in BuiltinTags.svg) {
            return TagResolution.Html(name, BuiltinTags.isVoid(name))
        }
        return TagResolution.Error(
            "Unknown tag '<$name>' (not a built-in HTML, special, or registered custom tag)",
        )
    }
}
