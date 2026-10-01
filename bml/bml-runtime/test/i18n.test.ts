import { afterEach, describe, expect, it } from "vitest"
import { readFileSync } from "node:fs"
import { format, loadMessages, selectCategory, setMessages, t, type MessageCatalog } from "../src/i18n"

/**
 * The TypeScript half of the shared client/server parity contract: every
 * case in `parity/i18n-vectors.json` must produce the same string here and in the Kotlin
 * resolver (core-bml's I18nParityTest runs the other half).
 */
interface Vectors {
  defaultLocale: string
  catalogs: Record<string, { messages: Record<string, string>; plurals: Record<string, Record<string, string>> }>
  cases: Array<{
    locale: string
    key: string
    count?: number
    args?: Record<string, unknown>
    expected: string
  }>
}

// vitest runs with cwd = bml-runtime; the fixture is shared with core-bml's I18nParityTest.
const vectors: Vectors = JSON.parse(readFileSync("test/parity/i18n-vectors.json", "utf-8"))

afterEach(() => setMessages(null))

describe("i18n parity vectors", () => {
  for (const c of vectors.cases) {
    it(`${c.locale} ${c.key} count=${c.count ?? "-"} -> ${c.expected}`, () => {
      const catalog = vectors.catalogs[c.locale]
      setMessages({ locale: c.locale, ...catalog } as MessageCatalog)
      const actual = c.count !== undefined ? t(c.key, c.count, c.args ?? {}) : t(c.key, c.args ?? {})
      expect(actual).toBe(c.expected)
    })
  }
})

describe("loadMessages", () => {
  it("fetches the catalog for the document language and installs it", async () => {
    document.documentElement.lang = "es-419"
    const calls: string[] = []
    globalThis.fetch = (async (url: string) => {
      calls.push(String(url))
      return {
        ok: true,
        json: async () => ({ locale: "es-419", messages: { "home.title": "Bienvenido" }, plurals: {} }),
      }
    }) as unknown as typeof fetch
    await loadMessages()
    expect(calls).toEqual(["/_bml/i18n/es-419.json"])
    expect(t("home.title")).toBe("Bienvenido")
  })

  it("throws on an http error and keeps the previous catalog", async () => {
    setMessages({ locale: "en", messages: { k: "kept" }, plurals: {} })
    globalThis.fetch = (async () => ({ ok: false, status: 503 })) as unknown as typeof fetch
    await expect(loadMessages("en")).rejects.toThrow("BML i18n HTTP 503")
    expect(t("k")).toBe("kept")
  })
})

describe("cldr differential fixture", () => {
  it("matches what this Node's ICU derives — regenerate via tools/generate-cldr-plurals.mjs on drift", () => {
    interface Fixture { counts: number[]; categories: Record<string, string> }
    const fixture: Fixture = JSON.parse(readFileSync("test/parity/cldr-plurals.json", "utf-8"))
    const letter: Record<string, string> = { zero: "z", one: "o", two: "t", few: "f", many: "m", other: "x" }
    const drifted: string[] = []
    for (const [language, expected] of Object.entries(fixture.categories)) {
      const rules = new Intl.PluralRules(language)
      const actual = fixture.counts.map(n => letter[rules.select(n)]).join("")
      if (actual !== expected) drifted.push(language)
    }
    expect(drifted).toEqual([])
  })
})

describe("format and category edges", () => {
  it("without a catalog t renders the key", () => {
    expect(t("no.catalog")).toBe("no.catalog")
    expect(t("no.catalog", 3)).toBe("no.catalog")
  })

  it("format leaves unknown placeholders and renders null empty", () => {
    expect(format("Hi {name}{tail}", { name: null })).toBe("Hi {tail}")
  })

  it("negative counts select by absolute value", () => {
    expect(selectCategory("en", -1)).toBe("ONE")
    expect(selectCategory("pl", -3)).toBe("FEW")
  })

  it("the plural count cannot be overridden by formatting arguments", () => {
    setMessages({ locale: "en", messages: {}, plurals: { items: { ONE: "{count} item", OTHER: "{count} items" } } })
    expect(t("items", 1, { count: 99 })).toBe("1 item")
  })
})
