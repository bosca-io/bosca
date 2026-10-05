/**
 * # HTML Data Attribute Convention (`data-ba-*`)
 *
 * All analytics attributes use the `data-ba-` prefix. Auto-instrumentation
 * trackers (click, visibility, form) call {@link extractAttributes} to collect
 * them into every emitted event.
 *
 * ## Identification & Control
 *
 * | Attribute              | Purpose                                                     |
 * |------------------------|-------------------------------------------------------------|
 * | `data-ba-id="name"`    | Stable element ID for heat maps — short-circuits the CSS    |
 * |                        | selector walk in `getElementIdentifier`                     |
 * | `data-ba-track="label"`| Opts element into impression tracking; label → `track_id`   |
 * | `data-ba-no-text`      | Suppresses `element_text` capture — use on containers whose |
 * |                        | `textContent` would be noisy (product cards, article cards) |
 *
 * ## Custom Extras
 *
 * Any `data-ba-{key}` attribute (not a content field) maps into `extras`.
 * Kebab-case is converted to snake_case.
 *
 * ```html
 * <button data-ba-action="add-to-cart" data-ba-campaign="summer">Buy</button>
 * ```
 * → `extras: { action: "add_to_cart", campaign: "summer" }`
 *
 * Extras **inherit upward**: a child inherits ancestors' `data-ba-*` extras.
 * Child values win on key collision.
 * Programmatic events set the same values directly on `element.extras`.
 *
 * ## Content References
 *
 * | Attribute                       | Required | Maps to             |
 * |---------------------------------|----------|---------------------|
 * | `data-ba-content-id="id"`       | yes      | `content[].id`      |
 * | `data-ba-content-type="type"`   | no       | `content[].type`    |
 * | `data-ba-content-index="n"`     | no       | `content[].index`   |
 * | `data-ba-content-percent="n"`   | no       | `content[].percent` |
 *
 * Elements with `data-ba-content-id` are **automatically observed** for
 * impressions — no `data-ba-track` needed. Content inherits upward like
 * extras; multiple ancestors produce multiple `content[]` entries.
 *
 * ## Example: Product Listing
 *
 * ```html
 * <section data-ba-id="featured" data-ba-section="featured-products">
 *   <div data-ba-content-id="prod-42" data-ba-content-type="product"
 *        data-ba-content-index="0" data-ba-no-text>
 *     <h3>Widget Pro</h3>
 *     <p>$29.99</p>
 *     <button data-ba-action="add-to-cart">Add to Cart</button>
 *   </div>
 * </section>
 * ```
 *
 * **Impression** (prod-42 visible for 1s):
 * ```json
 * { "type": "impression",
 *   "element": {
 *     "id": "[data-ba-id=\"featured\"] > div",
 *     "type": "div",
 *     "extras": { "path": "/shop", "section": "featured_products" },
 *     "content": [{ "id": "prod-42", "type": "product", "index": 0 }] } }
 * ```
 *
 * **Click** ("Add to Cart"):
 * ```json
 * { "type": "interaction",
 *   "element": {
 *     "id": "[data-ba-id=\"featured\"] > div > button",
 *     "type": "click",
 *     "extras": { "element_text": "Add to Cart",
 *                 "action": "add_to_cart",
 *                 "section": "featured_products", "..." },
 *     "content": [{ "id": "prod-42", "type": "product", "index": 0 }] } }
 * ```
 *
 * Note: `action` comes from the button, `section` from the ancestor, and
 * `content` from the product card `<div>`.
 *
 * ## Example: Article Feed
 *
 * ```html
 * <main data-ba-id="feed" data-ba-feed-type="personalized">
 *   <article data-ba-content-id="article-901" data-ba-content-type="article"
 *            data-ba-content-index="0" data-ba-no-text
 *            data-ba-category="technology">
 *     <h2><a href="/articles/901" data-ba-action="read">AI in 2026</a></h2>
 *     <button data-ba-action="bookmark">Bookmark</button>
 *     <button data-ba-action="share">Share</button>
 *   </article>
 * </main>
 * ```
 *
 * Clicking "Bookmark" produces extras `{ action: "bookmark",
 * category: "technology", feed_type: "personalized" }` and
 * content `[{ id: "article-901", type: "article", index: 0 }]`.
 *
 * @module
 */

import type { IContentElement } from '../event'

const PREFIX = 'data-ba-'
const PREFIX_LEN = PREFIX.length
const CONTENT_SUB = 'content-'

/**
 * Result of extracting `data-ba-*` attributes from a DOM element and its
 * ancestors. Used by all auto-instrumentation trackers to enrich events.
 */
export interface ExtractedAttributes {
  extras: { [key: string]: string }
  content: IContentElement[]
}

const EMPTY: ExtractedAttributes = { extras: {}, content: [] }

/**
 * Single upward walk that collects both extras and content from the target
 * element and its ancestors. Returns a shared empty object when no
 * `data-ba-*` attributes are found anywhere in the chain (zero allocation
 * for undecorated elements).
 *
 * @example
 * ```ts
 * import { extractAttributes } from '@bosca/analytics-client-browser'
 *
 * const { extras, content } = extractAttributes(element)
 * // extras: { action: "add_to_cart", section: "featured_products" }
 * // content: [{ id: "prod-42", type: "product", index: 0 }]
 * ```
 */
export function extractAttributes(element: Element): ExtractedAttributes {
  let extras: { [key: string]: string } | null = null
  let content: IContentElement[] | null = null
  let current: Element | null = element

  while (current) {
    const attrs = current.attributes
    let contentId: string | null = null
    let contentType = ''
    let contentIndex: string | null = null
    let contentPercent: string | null = null

    for (let i = 0; i < attrs.length; i++) {
      const name = attrs[i]!.name
      if (!name.startsWith(PREFIX)) continue

      const suffix = name.slice(PREFIX_LEN)

      if (suffix.startsWith(CONTENT_SUB)) {
        const field = suffix.slice(CONTENT_SUB.length)
        switch (field) {
          case 'id': contentId = attrs[i]!.value; break
          case 'type': contentType = attrs[i]!.value; break
          case 'index': contentIndex = attrs[i]!.value; break
          case 'percent': contentPercent = attrs[i]!.value; break
        }
        continue
      }

      const key = suffix.replace(/-/g, '_')
      if (!extras) extras = {}
      if (!(key in extras)) {
        extras[key] = attrs[i]!.value
      }
    }

    if (contentId !== null) {
      const item: IContentElement = { id: contentId, type: contentType }
      if (contentIndex !== null) item.index = Number(contentIndex)
      if (contentPercent !== null) item.percent = Number(contentPercent)
      if (!content) content = []
      content.push(item)
    }

    current = current.parentElement
  }

  if (!extras && !content) return EMPTY
  return { extras: extras || {}, content: content || [] }
}
