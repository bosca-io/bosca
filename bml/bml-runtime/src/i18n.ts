/**
 * Client-side localization. The server serves each locale's catalog with the
 * fallback chain ALREADY merged (`/_bml/i18n/<locale>.json`), so this helper is deliberately
 * small: fetch once, then category selection + `{name}` formatting that MIRROR the Kotlin
 * runtime (`bosca.bml.i18n.Messages` / `PluralRules`) — a shared parity fixture keeps the two
 * from drifting. Missing keys render the key itself, like the server-side `t()` function tier.
 *
 *   await loadMessages()                       // locale defaults to <html lang="…">
 *   label.textContent = t("cart.empty")
 *   count.textContent  = t("cart.items", items.length)
 *
 * Pages that never import these ship zero extra bytes — esbuild tree-shakes unused exports.
 */

/** URL prefix the bml-server serves merged per-locale catalogs under (same origin as the page). */
export const I18N_PREFIX = "/_bml/i18n/"

export interface MessageCatalog {
  locale: string
  messages: Record<string, string>
  plurals: Record<string, Record<string, string>>
}

let active: MessageCatalog | null = null

/**
 * Fetch and install the catalog for [locale] (default: the document's `<html lang>`, which BML
 * pages set from `ctx.lang`). Unsupported locales come back as the site default — same policy as
 * page negotiation, so this never 404s against a healthy server.
 */
export async function loadMessages(locale?: string): Promise<void> {
  const tag = locale || document.documentElement.lang || "en"
  const res = await fetch(`${I18N_PREFIX}${encodeURIComponent(tag)}.json`)
  if (!res.ok) throw new Error(`BML i18n HTTP ${res.status}`)
  active = (await res.json()) as MessageCatalog
}

/** Install a catalog directly (tests, prefetched payloads); null clears it. */
export function setMessages(catalog: MessageCatalog | null): void {
  active = catalog
}

/** Look up [key]; with a numeric second argument, select the plural form for that count. */
export function t(key: string, args?: Record<string, unknown>): string
export function t(key: string, count: number, args?: Record<string, unknown>): string
export function t(key: string, countOrArgs?: number | Record<string, unknown>, args?: Record<string, unknown>): string {
  if (typeof countOrArgs === "number") return plural(key, countOrArgs, args ?? {})
  return format(active?.messages[key] ?? key, countOrArgs ?? {})
}

function plural(key: string, count: number, args: Record<string, unknown>): string {
  const withCount = { ...args, count }
  const forms = active?.plurals[key]
  if (forms) {
    // Per-category form, falling back to the locale's OTHER — same rule as MessageCatalog.plural.
    const form = forms[selectCategory(active!.locale, count)] ?? forms.OTHER
    if (form !== undefined) return format(form, withCount)
  }
  // A plural call against a plain entry degrades to that entry; a missing key renders the key.
  return format(active?.messages[key] ?? key, withCount)
}

/**
 * `{name}` substitution, mirroring the Kotlin `Messages.format`: only names present in [args]
 * are touched (a missing argument stays visible), null/undefined values render empty, literal
 * braces pass through.
 */
export function format(template: string, args: Record<string, unknown>): string {
  if (!template.includes("{")) return template
  return template.replace(/\{([A-Za-z0-9_]+)\}/g, (match, name: string) =>
    name in args ? String(args[name] ?? "") : match,
  )
}

/**
 * CLDR cardinal category for a count — the browser's own CLDR via `Intl.PluralRules` (every
 * language, always current, zero bytes shipped). The JVM has no equivalent without ICU4J, so
 * the Kotlin side keeps its hand-written table — the shared parity vectors pin that table
 * against this real CLDR implementation, which is exactly the drift alarm we want.
 */
export function selectCategory(locale: string, count: number): string {
  try {
    return new Intl.PluralRules(locale).select(Math.abs(count)).toUpperCase()
  } catch {
    // An unparseable tag: the same ONE/OTHER split the Kotlin side uses for unknown languages.
    return Math.abs(count) === 1 ? "ONE" : "OTHER"
  }
}
