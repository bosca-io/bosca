/**
 * Editor helpers for the ecom `ProductConfiguration` polymorphic union. The API accepts it as a JSON
 * object with a `type` discriminator (decoded by the controller dispatcher into the sealed Kotlin
 * type) and returns it as a GraphQL union whose members are the Kotlin class simple names. These map
 * a flat editable draft <-> the wire shapes for both directions.
 */

export interface ProductConfigDraft {
  type: 'standard' | 'clothing' | 'subscription'
  /** Comma-separated sizes, edited as text (clothing). */
  sizes: string
  /** Plan group id (subscription). */
  planGroupId: string
}

export const PRODUCT_CONFIG_TYPE_OPTIONS = [
  { value: 'standard', label: 'Standard' },
  { value: 'clothing', label: 'Clothing (sizes)' },
  { value: 'subscription', label: 'Subscription (plan group)' },
]

export function emptyProductConfigDraft(): ProductConfigDraft {
  return { type: 'standard', sizes: '', planGroupId: '' }
}

/** Builds the JSON the `configuration` input expects (the `@SerialName` discriminator + fields). */
export function buildProductConfiguration(d: ProductConfigDraft): Record<string, unknown> {
  if (d.type === 'clothing') {
    return { type: 'clothing', sizes: d.sizes.split(',').map(s => s.trim()).filter(Boolean) }
  }
  if (d.type === 'subscription') {
    return { type: 'subscription', planGroupId: d.planGroupId.trim() }
  }
  return { type: 'standard' }
}

/** Seeds an editable draft from a product's `configuration` union (queried with `__typename`). */
export function parseProductConfiguration(
  c: { __typename?: string; sizes?: string[]; planGroupId?: string } | null | undefined,
): ProductConfigDraft {
  if (c?.__typename === 'ClothingProductConfiguration') {
    return { type: 'clothing', sizes: (c.sizes ?? []).join(', '), planGroupId: '' }
  }
  if (c?.__typename === 'SubscriptionProductConfiguration') {
    return { type: 'subscription', sizes: '', planGroupId: c.planGroupId ?? '' }
  }
  return emptyProductConfigDraft()
}

/** Product types offered when authoring a product (SHIPPING/PROMOTION are system-synthesized). */
export const PRODUCT_TYPE_OPTIONS = [
  { value: 'PHYSICAL', label: 'Physical' },
  { value: 'VIRTUAL', label: 'Virtual' },
  { value: 'SERVICE', label: 'Service' },
  { value: 'SHIPPING', label: 'Shipping (carrier line)' },
  { value: 'SUBSCRIPTION', label: 'Subscription' },
]

/** All product types, plus an "all" sentinel — for the catalog-products type filter. */
export const PRODUCT_TYPE_FILTER_OPTIONS = [
  { value: '', label: 'All types' },
  { value: 'PHYSICAL', label: 'Physical' },
  { value: 'VIRTUAL', label: 'Virtual' },
  { value: 'SERVICE', label: 'Service' },
  { value: 'SHIPPING', label: 'Shipping' },
  { value: 'PROMOTION', label: 'Promotion' },
  { value: 'SUBSCRIPTION', label: 'Subscription' },
]

// --- CatalogProductExtras union editor (none | quantityRequirements) ---

export interface CatalogExtrasDraft {
  type: 'none' | 'quantityRequirements'
  /** null = unset (the min/max fields are nullable). Bound to NumberInput (number | null). */
  min: number | null
  max: number | null
}

export const CATALOG_EXTRAS_TYPE_OPTIONS = [
  { value: 'none', label: 'None' },
  { value: 'quantityRequirements', label: 'Quantity requirements' },
]

export function emptyCatalogExtrasDraft(): CatalogExtrasDraft {
  return { type: 'none', min: null, max: null }
}

export function buildCatalogExtras(d: CatalogExtrasDraft): Record<string, unknown> {
  if (d.type === 'quantityRequirements') {
    const out: Record<string, unknown> = { type: 'quantityRequirements' }
    if (d.min != null) out.min = d.min
    if (d.max != null) out.max = d.max
    return out
  }
  return { type: 'none' }
}

export function parseCatalogExtras(
  c: { __typename?: string; min?: number | null; max?: number | null } | null | undefined,
): CatalogExtrasDraft {
  if (c?.__typename === 'QuantityRequirements') {
    return { type: 'quantityRequirements', min: c.min ?? null, max: c.max ?? null }
  }
  return emptyCatalogExtrasDraft()
}

// --- ProviderConfiguration (payment/shipping provider settings) ---
// Asymmetric boundary: the GraphQL OUTPUT projects the Kotlin `Map<String,String>` as a typed list
// (`KeyValueProviderConfiguration.values: [ProviderConfigurationValue{key,value}]`), but the INPUT
// JSON scalar decodes back into the map — so `parse` reads a list and `build` emits a `{key:value}`
// object. Secret values are never sent to this client on read paths; the editor only writes them.

export interface ProviderConfigEntry { key: string; value: string }

export interface ProviderConfigDraft {
  type: 'none' | 'keyValue'
  entries: ProviderConfigEntry[]
}

export const PROVIDER_CONFIG_TYPE_OPTIONS = [
  { value: 'none', label: 'None' },
  { value: 'keyValue', label: 'Key / value settings' },
]

export function emptyProviderConfigDraft(): ProviderConfigDraft {
  return { type: 'none', entries: [] }
}

/** Builds the JSON-scalar input: `{ type: 'none' }` or `{ type: 'keyValue', values: {k:v,…} }`. */
export function buildProviderConfiguration(d: ProviderConfigDraft): Record<string, unknown> {
  if (d.type === 'keyValue') {
    const values: Record<string, string> = {}
    for (const e of d.entries) {
      const k = e.key.trim()
      if (k) values[k] = e.value
    }
    return { type: 'keyValue', values }
  }
  return { type: 'none' }
}

/** Parses the OUTPUT union (`__typename` + projected `values` list) into an editor draft. */
export function parseProviderConfiguration(
  c: { __typename?: string; values?: { key: string; value: string }[] | null } | null | undefined,
): ProviderConfigDraft {
  if (c?.__typename === 'KeyValueProviderConfiguration') {
    return { type: 'keyValue', entries: (c.values ?? []).map(v => ({ key: v.key, value: v.value })) }
  }
  return emptyProviderConfigDraft()
}

/** StoreType enum options (VIRTUAL = online, PHYSICAL = in-person). */
export const STORE_TYPE_OPTIONS = [
  { value: 'VIRTUAL', label: 'Virtual (online)' },
  { value: 'PHYSICAL', label: 'Physical (in-person)' },
]

// --- Promotion Rule (polymorphic discount behavior) ---
// A promotion's `type` (CART vs SUBSCRIPTION) constrains which rule variants are valid. The editor
// keeps one flat draft (only the fields for the active variant matter) and the page resets the
// variant when the promotion type flips. Input is the JSON scalar `{type, …}`; output is the `Rule`
// union read by `__typename`. `amount` is the Money scalar (a decimal string), like catalog prices.

export const PROMOTION_TYPE_OPTIONS = [
  { value: 'CART', label: 'Cart' },
  { value: 'SUBSCRIPTION', label: 'Subscription renewal' },
]

export type RuleType =
  | 'cartPercentOff' | 'cartAmountOff' | 'cartFreeShipping' | 'cartBuyOneGetOne'
  | 'subscriptionPercentOff' | 'subscriptionAmountOff'

export interface RuleDraft {
  type: RuleType
  percent: number | null
  amount: string
  buyQuantity: number | null
  getQuantity: number | null
}

export const RULE_TYPE_OPTIONS_CART = [
  { value: 'cartPercentOff', label: 'Percent off cart' },
  { value: 'cartAmountOff', label: 'Amount off cart' },
  { value: 'cartFreeShipping', label: 'Free shipping' },
  { value: 'cartBuyOneGetOne', label: 'Buy X get Y free' },
]
export const RULE_TYPE_OPTIONS_SUBSCRIPTION = [
  { value: 'subscriptionPercentOff', label: 'Percent off renewal' },
  { value: 'subscriptionAmountOff', label: 'Amount off renewal' },
]

/** The default rule variant for a promotion type ('CART' | 'SUBSCRIPTION'). */
export function defaultRuleType(promotionType: string): RuleType {
  return promotionType === 'SUBSCRIPTION' ? 'subscriptionPercentOff' : 'cartPercentOff'
}

export function emptyRuleDraft(): RuleDraft {
  return { type: 'cartPercentOff', percent: 10, amount: '', buyQuantity: 1, getQuantity: 1 }
}

/** Builds the JSON-scalar `rule` input with its `type` discriminator. */
export function buildRule(d: RuleDraft): Record<string, unknown> {
  switch (d.type) {
    case 'cartPercentOff':
    case 'subscriptionPercentOff':
      return { type: d.type, percent: Number(d.percent ?? 0) }
    case 'cartAmountOff':
    case 'subscriptionAmountOff':
      return { type: d.type, amount: d.amount.trim() }
    case 'cartBuyOneGetOne':
      return { type: d.type, buyQuantity: Number(d.buyQuantity ?? 1), getQuantity: Number(d.getQuantity ?? 1) }
    case 'cartFreeShipping':
      return { type: d.type }
  }
}

interface RuleOutput {
  __typename?: string
  percent?: number | null
  amount?: string | null
  buyQuantity?: number | null
  getQuantity?: number | null
}

/** Parses the `Rule` output union (`__typename` + variant fields) into an editor draft. */
export function parseRule(r: RuleOutput | null | undefined): RuleDraft {
  const base = emptyRuleDraft()
  switch (r?.__typename) {
    case 'PercentOffCartRule': return { ...base, type: 'cartPercentOff', percent: r.percent ?? 0 }
    case 'AmountOffCartRule': return { ...base, type: 'cartAmountOff', amount: r.amount ?? '' }
    case 'FreeShippingCartRule': return { ...base, type: 'cartFreeShipping' }
    case 'BuyOneGetOneRule': return { ...base, type: 'cartBuyOneGetOne', buyQuantity: r.buyQuantity ?? 1, getQuantity: r.getQuantity ?? 1 }
    case 'PercentOffSubscriptionRule': return { ...base, type: 'subscriptionPercentOff', percent: r.percent ?? 0 }
    case 'AmountOffSubscriptionRule': return { ...base, type: 'subscriptionAmountOff', amount: r.amount ?? '' }
    default: return base
  }
}

/** A short human summary of a `Rule` output union, for list rows. */
export function formatRuleSummary(r: RuleOutput | null | undefined): string {
  switch (r?.__typename) {
    case 'PercentOffCartRule': return `${r.percent}% off cart`
    case 'AmountOffCartRule': return `${formatMoney(r.amount)} off cart`
    case 'FreeShippingCartRule': return 'Free shipping'
    case 'BuyOneGetOneRule': return `Buy ${r.buyQuantity} get ${r.getQuantity} free`
    case 'PercentOffSubscriptionRule': return `${r.percent}% off renewal`
    case 'AmountOffSubscriptionRule': return `${formatMoney(r.amount)} off renewal`
    default: return '—'
  }
}

// --- Payments (tender types at checkout) ---
export const PAYMENT_TYPE_OPTIONS = [
  { value: 'CREDIT_CARD', label: 'Credit card' },
  { value: 'CASH', label: 'Cash' },
  { value: 'CHECK', label: 'Check' },
  { value: 'ACCOUNT_CREDIT', label: 'Account credit' },
  { value: 'COMPANY_CREDIT', label: 'Company / store credit' },
]

/** Detects the card brand from the number's IIN prefix (the gateway re-derives it too). */
export function detectCardBrand(raw: string): string {
  const n = raw.replace(/\D/g, '')
  if (/^4/.test(n)) return 'VISA'
  if (/^(5[1-5]|2(2[2-9]|[3-6]\d|7[01]|720))/.test(n)) return 'MASTERCARD'
  if (/^3[47]/.test(n)) return 'AMERICAN_EXPRESS'
  if (/^(6011|65|64[4-9]|622)/.test(n)) return 'DISCOVER'
  return 'UNKNOWN'
}

/** Luhn checksum — a card number must pass this to be structurally valid. */
export function luhnValid(raw: string): boolean {
  const n = raw.replace(/\D/g, '')
  if (n.length < 12) return false
  let sum = 0
  let alt = false
  for (let i = n.length - 1; i >= 0; i--) {
    let d = n.charCodeAt(i) - 48
    if (d < 0 || d > 9) return false
    if (alt) { d *= 2; if (d > 9) d -= 9 }
    sum += d
    alt = !alt
  }
  return sum % 10 === 0
}

const CARD_BRAND_LABELS: Record<string, string> = {
  VISA: 'Visa', MASTERCARD: 'Mastercard', AMERICAN_EXPRESS: 'American Express', DISCOVER: 'Discover', UNKNOWN: 'Card',
}
export function cardBrandLabel(type: string): string {
  return CARD_BRAND_LABELS[type] ?? 'Card'
}

// --- Fulfillment ---
// Inventory-sync connector keys. 'manual' (no external sync) is the only connector for now; real
// connectors (Shippo etc.) are a follow-up spec — when they land as DI-registered SPIs this should
// move to a backend enumeration (like the provider keys).
export const CONNECTOR_KEY_OPTIONS = [
  { value: 'manual', label: 'Manual (no external sync)' },
]

// --- Dimension & weight units (per company) ---
// A company stores its product/container dimensions in one length unit and weights in one weight
// unit (the packer compares relative magnitudes; carrier providers convert from these at label time).
// Values mirror the `LengthUnit` / `WeightUnit` GraphQL enums.
export const LENGTH_UNIT_OPTIONS = [
  { value: 'INCHES', label: 'Inches (in)' },
  { value: 'CENTIMETERS', label: 'Centimeters (cm)' },
]
export const WEIGHT_UNIT_OPTIONS = [
  { value: 'POUNDS', label: 'Pounds (lb)' },
  { value: 'KILOGRAMS', label: 'Kilograms (kg)' },
]

/** Short suffix for a length unit ("in" / "cm"), for input and column labels. Defaults to inches. */
export function lengthUnitAbbr(unit: string | null | undefined): string {
  return unit === 'CENTIMETERS' ? 'cm' : 'in'
}

/** Short suffix for a weight unit ("lb" / "kg"), for input and column labels. Defaults to pounds. */
export function weightUnitAbbr(unit: string | null | undefined): string {
  return unit === 'KILOGRAMS' ? 'kg' : 'lb'
}

// --- Audit log ---
// The entityType strings the backend writes to ecom.audit (a controlled vocabulary, not mock data —
// these mirror EcomAuditService.record(entityType=...) call sites). "" = no filter.
export const AUDIT_ENTITY_TYPE_OPTIONS = [
  { value: '', label: 'All entity types' },
  { value: 'company', label: 'Company' },
  { value: 'company_credit', label: 'Company credit' },
  { value: 'customer', label: 'Customer' },
  { value: 'account', label: 'Account' },
  { value: 'store', label: 'Store' },
  { value: 'payment_provider', label: 'Payment provider' },
  { value: 'shipping_provider', label: 'Shipping provider' },
  { value: 'catalog', label: 'Catalog' },
  { value: 'manufacturer', label: 'Manufacturer' },
  { value: 'product', label: 'Product' },
  { value: 'catalog_product', label: 'Catalog product' },
  { value: 'inventory', label: 'Inventory' },
  { value: 'fulfillment_center', label: 'Fulfillment center' },
  { value: 'promotion', label: 'Promotion' },
  { value: 'subscription_plan_group', label: 'Plan group' },
  { value: 'subscription_plan', label: 'Plan' },
  { value: 'subscription', label: 'Subscription' },
  { value: 'cart', label: 'Cart' },
  { value: 'payment', label: 'Payment' },
]

// --- Customers & billing accounts ---

/** AccountType enum options. */
export const ACCOUNT_TYPE_OPTIONS = [
  { value: 'CONSUMER', label: 'Consumer' },
  { value: 'BUSINESS', label: 'Business' },
]

/** AddressType enum options (where the bill goes vs. where goods go). */
export const ADDRESS_TYPE_OPTIONS = [
  { value: 'SHIPPING', label: 'Shipping' },
  { value: 'BILLING', label: 'Billing' },
]

// --- Subscription plans ---

/** IntervalUnit enum options (billing cadence unit). */
export const INTERVAL_UNIT_OPTIONS = [
  { value: 'DAYS', label: 'Days' },
  { value: 'MONTHS', label: 'Months' },
  { value: 'YEARS', label: 'Years' },
  { value: 'HOURS', label: 'Hours' },
  { value: 'MINUTES', label: 'Minutes' },
  { value: 'SECONDS', label: 'Seconds' },
]

/**
 * PlanConfiguration is a single-variant union for now (only `standard`). The editor is a
 * one-option select kept for forward-compatibility — when typed plan variants land, add them here.
 */
export const PLAN_CONFIG_TYPE_OPTIONS = [
  { value: 'standard', label: 'Standard' },
]

export function buildPlanConfiguration(type: string): Record<string, unknown> {
  return { type: type || 'standard' }
}

/** "every 1 month" / "every 3 months" from a plan/subscription interval + unit. */
export function formatInterval(interval: number, unit: string): string {
  const u = unit.toLowerCase()
  const singular = interval === 1 ? u.replace(/s$/, '') : u
  return `every ${interval} ${singular}`
}

/**
 * The order's furthest-along fulfillment stage, for a one-badge summary. A cart carries a SET of
 * CartStatusFlags; this picks the most-advanced fulfillment flag present (COMPLETE > SHIPPED >
 * SHIPPING > PREPARING), falling back to PAID (every order is at least paid).
 */
const FULFILLMENT_FLAGS = ['COMPLETE', 'SHIPPED', 'SHIPPING', 'PREPARING', 'PAID'] as const
export function fulfillmentStage(status: string[]): string {
  return FULFILLMENT_FLAGS.find(f => status.includes(f)) ?? 'PAID'
}

/**
 * Formats the `Money` scalar (a scale-4 decimal string like "19.9900") in the given ISO-4217
 * [currency] (the store's; defaults to USD), e.g. "$19.99" / "€19.99" / "£19.99". An unknown or blank
 * currency code falls back to a plain `CODE 19.99` form rather than throwing.
 */
export function formatMoney(s: string | null | undefined, currency: string | null | undefined = 'USD'): string {
  if (s == null) return '—'
  const n = Number(s)
  if (!Number.isFinite(n)) return s
  const code = (currency || 'USD').toUpperCase()
  try {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: code }).format(n)
  } catch {
    return `${code} ${n.toFixed(2)}`
  }
}

/** datetime-local input value ("YYYY-MM-DDTHH:mm") from an ISO instant, or '' when null. */
export function isoToLocalInput(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '' : d.toISOString().slice(0, 16)
}

/** ISO instant from a datetime-local input value, or null when empty. */
export function localInputToIso(v: string): string | null {
  if (!v) return null
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? null : d.toISOString()
}
