package bosca.bml.render

/**
 * The streaming HTML output target that compiler-generated BML render functions
 * write to (emits calls against this API; is the renderer).
 */
class HtmlWriter(private val out: StringBuilder = StringBuilder()) {

    /** Append literal, pre-formed markup (tag text the compiler emitted verbatim). */
    fun markup(literal: String): HtmlWriter {
        out.append(literal)
        return this
    }

    /** Append HTML-escaped text from a `{ expr }` interpolation. */
    fun text(value: Any?): HtmlWriter {
        Html.appendEscaped(out, value?.toString().orEmpty())
        return this
    }

    /** Append raw, unescaped HTML from a `{@ expr }` interpolation (trusted). */
    fun raw(value: Any?): HtmlWriter {
        out.append(value?.toString().orEmpty())
        return this
    }

    /** Append a dynamic attribute: `null`/`false` omit it, `true` renders the bare name. */
    fun attr(name: String, value: Any?): HtmlWriter {
        when (value) {
            null, false -> {}
            true -> out.append(' ').append(name)
            else -> {
                out.append(' ').append(name).append("=\"")
                Html.appendEscaped(out, value.toString())
                out.append('"')
            }
        }
        return this
    }

    /** Merge a `Map<String, Any?>` of dynamic attributes (`{...expr}`). */
    fun spread(attrs: Map<String, Any?>): HtmlWriter {
        for ((k, v) in attrs) attr(k, v)
        return this
    }

    val length: Int get() = out.length

    override fun toString(): String = out.toString()
}
