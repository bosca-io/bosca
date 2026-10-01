package bosca.bml.i18n

import bosca.bml.render.currentRenderContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * The localization resolver: fallback across locales, `{name}` placeholder
 * substitution, CLDR plural selection, and the never-throw missing-key policy. Pure of HTTP and
 * transport — a [MessageSource] supplies catalogs; templates reach this through the ambient
 * [t] functions, which read the render's locale and source from the coroutine context.
 */
object Messages {

    private val log = LoggerFactory.getLogger(Messages::class.java)

    // Warn-once bookkeeping (per key, process-wide): a missing translation is a content gap the
    // logs should name exactly once, not a render-rate log storm.
    private val warnedKeys = ConcurrentHashMap.newKeySet<String>()

    /**
     * The lookup chain for [locale]: the locale and its progressive truncations
     * (`zh-Hant-TW` -> `zh-Hant` -> `zh`), then the source's [default] and its truncations,
     * deduped — the same shape as messages-pages' fallback, generalized past two levels.
     */
    fun fallbackChain(locale: Locale, default: Locale): List<Locale> = buildList {
        addAll(truncations(locale.toLanguageTag()))
        addAll(truncations(default.toLanguageTag()))
    }.distinct().map(Locale::forLanguageTag)

    private fun truncations(tag: String): List<String> = buildList {
        var current = tag
        while (true) {
            add(current)
            val cut = current.lastIndexOf('-')
            if (cut < 0) break
            current = current.substring(0, cut)
        }
    }

    /** Resolves and formats a plain message; the key itself (warned once) when nothing carries it. */
    suspend fun resolve(
        source: MessageSource,
        locale: Locale,
        key: String,
        args: Map<String, Any?> = emptyMap(),
    ): String {
        for (candidate in fallbackChain(locale, source.defaultLocale)) {
            source.catalog(candidate).message(key)?.let { return format(it, args) }
        }
        return missing(key)
    }

    /**
     * Resolves and formats a plural message. The CLDR category is selected per CANDIDATE locale —
     * each language's own rules pick among its own forms, so a Polish request that falls back to
     * the English catalog uses English's ONE/OTHER split, not Polish's FEW/MANY, for the English
     * text. The count is always available to the template as `{count}`. A plural call against a
     * key that only has a plain entry degrades to that entry (still formatted); a fully missing
     * key returns the key (warned once).
     */
    suspend fun resolvePlural(
        source: MessageSource,
        locale: Locale,
        key: String,
        count: Long,
        args: Map<String, Any?> = emptyMap(),
    ): String {
        val withCount = args + (COUNT to count)
        for (candidate in fallbackChain(locale, source.defaultLocale)) {
            val catalog = source.catalog(candidate)
            catalog.plural(key, PluralRules.select(candidate, count))?.let { return format(it, withCount) }
            catalog.message(key)?.let { return format(it, withCount) }
        }
        return missing(key)
    }

    /**
     * The `t`-attribute path: resolve [key] through the fallback chain, and
     * when no catalog carries it, format the AUTHORED [fallback] template — keys never render
     * from this path, an untranslated site shows exactly what the author wrote.
     */
    suspend fun resolveOr(
        source: MessageSource,
        locale: Locale,
        key: String,
        fallback: String,
        args: Map<String, Any?> = emptyMap(),
    ): String {
        for (candidate in fallbackChain(locale, source.defaultLocale)) {
            source.catalog(candidate).message(key)?.let { return format(it, args) }
        }
        return format(fallback, args)
    }

    /**
     * The plural `t`-attribute path: catalog plural rows through the fallback chain (category
     * selected per candidate locale), then the AUTHORED [fallbackForms] — selected with the
     * SOURCE language's plural rules, because the authored text is source-language text — with
     * the usual category-then-OTHER rule. `{count}` is always available.
     */
    suspend fun resolvePluralOr(
        source: MessageSource,
        locale: Locale,
        key: String,
        count: Long,
        fallbackForms: Map<PluralCategory, String>,
        args: Map<String, Any?> = emptyMap(),
    ): String {
        val withCount = args + (COUNT to count)
        for (candidate in fallbackChain(locale, source.defaultLocale)) {
            val catalog = source.catalog(candidate)
            catalog.plural(key, PluralRules.select(candidate, count))?.let { return format(it, withCount) }
        }
        val category = PluralRules.select(source.defaultLocale, count)
        val fallback = fallbackForms[category] ?: fallbackForms[PluralCategory.OTHER] ?: return missing(key)
        return format(fallback, withCount)
    }

    /**
     * Substitutes `{name}` placeholders from [args]. Only names present in [args] are touched —
     * unknown placeholders and literal braces pass through unchanged (a missing argument stays
     * visible in the output instead of vanishing); a null argument renders empty.
     */
    fun format(template: String, args: Map<String, Any?>): String {
        if (args.isEmpty() || '{' !in template) return template
        return PLACEHOLDER.replace(template) { match ->
            val name = match.groupValues[1]
            if (name in args) args[name]?.toString().orEmpty() else match.value
        }
    }

    private fun missing(key: String): String {
        if (warnedKeys.add(key)) {
            log.warn("bml i18n: no message for key '{}' in any locale of the fallback chain; rendering the key", key)
        }
        return key
    }

    /** Test hook: forget which keys have been warned about (the set is process-wide). */
    internal fun resetWarnings() = warnedKeys.clear()

    private val PLACEHOLDER = Regex("""\{([A-Za-z0-9_]+)}""")

    /** The implicit placeholder every plural template can reference. */
    const val COUNT: String = "count"
}

/**
 * Looks up the localized message for [key] in the ambient render's locale —
 * catalog fallback chain, then the key itself (never throws). This is the **computed-string**
 * tier: server scripts, island props, dynamic keys. For authored markup text prefer the `t`
 * element attribute, whose authored children are a guaranteed fallback.
 */
public suspend fun t(key: String): String {
    val ctx = currentRenderContext()
    return Messages.resolve(ctx.messages, ctx.locale, key)
}

/** [t] with `{name}` placeholder arguments: `t("cart.title", "name" to user.firstName)`. */
public suspend fun t(key: String, vararg args: Pair<String, Any?>): String {
    val ctx = currentRenderContext()
    return Messages.resolve(ctx.messages, ctx.locale, key, args.toMap())
}

/**
 * [t] with an authored [default]: resolves [key] through the fallback chain and, when no catalog
 * carries it, formats the authored default template instead of rendering the key — the
 * computed-string tier's counterpart of the `t` attribute's authored-fallback guarantee. Use it
 * for strings markup cannot carry (email subjects, component prop values): output stays exactly
 * what the author wrote until a translation exists, never a raw key. The manifest scanner
 * harvests the default as the key's source message, so `bosca bml i18n push` seeds it.
 */
public suspend fun t(key: String, default: String, vararg args: Pair<String, Any?>): String {
    val ctx = currentRenderContext()
    return Messages.resolveOr(ctx.messages, ctx.locale, key, default, args.toMap())
}

/**
 * Plural [t]: selects the CLDR category for [count] under the ambient locale's rules and formats
 * that form; `{count}` is implicitly available. `t("cart.items", items.size)`.
 */
public suspend fun t(key: String, count: Int, vararg args: Pair<String, Any?>): String =
    t(key, count.toLong(), *args)

/** Plural [t] for Long counts. */
public suspend fun t(key: String, count: Long, vararg args: Pair<String, Any?>): String {
    val ctx = currentRenderContext()
    return Messages.resolvePlural(ctx.messages, ctx.locale, key, count, args.toMap())
}
