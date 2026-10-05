import { describe, it, expect } from 'vitest'
import {
  detectCardBrand, luhnValid, cardBrandLabel,
  buildRule, parseRule, formatRuleSummary, defaultRuleType, emptyRuleDraft, type RuleDraft,
  buildProviderConfiguration, parseProviderConfiguration,
  buildProductConfiguration, parseProductConfiguration,
  buildCatalogExtras, parseCatalogExtras,
  buildPlanConfiguration, formatMoney, formatInterval,
  isoToLocalInput, localInputToIso, fulfillmentStage,
  lengthUnitAbbr, weightUnitAbbr,
} from './commerce'

describe('detectCardBrand', () => {
  it('detects each brand from the IIN prefix', () => {
    expect(detectCardBrand('4111 1111 1111 1111')).toBe('VISA')
    expect(detectCardBrand('4242424242424242')).toBe('VISA')
    expect(detectCardBrand('5555 5555 5555 4444')).toBe('MASTERCARD')
    expect(detectCardBrand('2221000000000009')).toBe('MASTERCARD')
    expect(detectCardBrand('378282246310005')).toBe('AMERICAN_EXPRESS')
    expect(detectCardBrand('6011111111111117')).toBe('DISCOVER')
  })
  it('returns UNKNOWN for empty or unrecognized prefixes', () => {
    expect(detectCardBrand('')).toBe('UNKNOWN')
    expect(detectCardBrand('9999999999999999')).toBe('UNKNOWN')
  })
})

describe('luhnValid', () => {
  it('accepts valid card numbers (ignoring spaces)', () => {
    expect(luhnValid('4242 4242 4242 4242')).toBe(true)
    expect(luhnValid('4111111111111111')).toBe(true)
    expect(luhnValid('5555555555554444')).toBe(true)
    expect(luhnValid('378282246310005')).toBe(true)
  })
  it('rejects bad checksums, too-short, and non-numeric', () => {
    expect(luhnValid('4111111111111112')).toBe(false)
    expect(luhnValid('123')).toBe(false)
    expect(luhnValid('not-a-card')).toBe(false)
  })
})

describe('cardBrandLabel', () => {
  it('labels known brands and falls back to Card', () => {
    expect(cardBrandLabel('VISA')).toBe('Visa')
    expect(cardBrandLabel('AMERICAN_EXPRESS')).toBe('American Express')
    expect(cardBrandLabel('UNKNOWN')).toBe('Card')
    expect(cardBrandLabel('WAT')).toBe('Card')
  })
})

describe('Rule union editor', () => {
  const draft = (over: Partial<RuleDraft>): RuleDraft => ({ ...emptyRuleDraft(), ...over })

  it('builds the JSON input with the @SerialName discriminator', () => {
    expect(buildRule(draft({ type: 'cartPercentOff', percent: 10 }))).toEqual({ type: 'cartPercentOff', percent: 10 })
    expect(buildRule(draft({ type: 'cartAmountOff', amount: '5.00' }))).toEqual({ type: 'cartAmountOff', amount: '5.00' })
    expect(buildRule(draft({ type: 'cartFreeShipping' }))).toEqual({ type: 'cartFreeShipping' })
    expect(buildRule(draft({ type: 'cartBuyOneGetOne', buyQuantity: 2, getQuantity: 1 }))).toEqual({ type: 'cartBuyOneGetOne', buyQuantity: 2, getQuantity: 1 })
  })

  it('parses the output union (__typename) back to a draft', () => {
    expect(parseRule({ __typename: 'PercentOffCartRule', percent: 15 })).toMatchObject({ type: 'cartPercentOff', percent: 15 })
    expect(parseRule({ __typename: 'BuyOneGetOneRule', buyQuantity: 3, getQuantity: 1 })).toMatchObject({ type: 'cartBuyOneGetOne', buyQuantity: 3, getQuantity: 1 })
    expect(parseRule({ __typename: 'AmountOffSubscriptionRule', amount: '2.0000' })).toMatchObject({ type: 'subscriptionAmountOff', amount: '2.0000' })
    expect(parseRule({ __typename: 'FreeShippingCartRule' })).toMatchObject({ type: 'cartFreeShipping' })
  })

  it('output __typename maps to the input discriminator on round-trip', () => {
    expect(buildRule(parseRule({ __typename: 'PercentOffCartRule', percent: 20 })).type === 'cartPercentOff').toBe(true)
    expect(buildRule(parseRule({ __typename: 'PercentOffCartRule', percent: 20 }))).toEqual({ type: 'cartPercentOff', percent: 20 })
  })

  it('defaultRuleType picks the right variant for the promotion type', () => {
    expect(defaultRuleType('CART')).toBe('cartPercentOff')
    expect(defaultRuleType('SUBSCRIPTION')).toBe('subscriptionPercentOff')
  })

  it('summarizes rules for list rows', () => {
    expect(formatRuleSummary({ __typename: 'PercentOffCartRule', percent: 10 })).toBe('10% off cart')
    expect(formatRuleSummary({ __typename: 'FreeShippingCartRule' })).toBe('Free shipping')
    expect(formatRuleSummary({ __typename: 'BuyOneGetOneRule', buyQuantity: 2, getQuantity: 1 })).toBe('Buy 2 get 1 free')
    expect(formatRuleSummary(null)).toBe('—')
  })
})

describe('ProviderConfiguration (asymmetric: list out, map in)', () => {
  it('builds a map and drops blank keys', () => {
    expect(buildProviderConfiguration({ type: 'none', entries: [] })).toEqual({ type: 'none' })
    expect(buildProviderConfiguration({ type: 'keyValue', entries: [{ key: 'apiKey', value: 'sk' }, { key: '  ', value: 'x' }, { key: '', value: 'y' }] }))
      .toEqual({ type: 'keyValue', values: { apiKey: 'sk' } })
  })
  it('parses the projected list of entries back', () => {
    expect(parseProviderConfiguration({ __typename: 'KeyValueProviderConfiguration', values: [{ key: 'a', value: 'b' }] }))
      .toEqual({ type: 'keyValue', entries: [{ key: 'a', value: 'b' }] })
    expect(parseProviderConfiguration({ __typename: 'EmptyProviderConfiguration' })).toEqual({ type: 'none', entries: [] })
  })
})

describe('ProductConfiguration', () => {
  it('builds clothing sizes from CSV and trims/drops blanks', () => {
    expect(buildProductConfiguration({ type: 'clothing', sizes: 'S, M , L,', planGroupId: '' }))
      .toEqual({ type: 'clothing', sizes: ['S', 'M', 'L'] })
    expect(buildProductConfiguration({ type: 'subscription', sizes: '', planGroupId: ' pg ' }))
      .toEqual({ type: 'subscription', planGroupId: 'pg' })
    expect(buildProductConfiguration({ type: 'standard', sizes: '', planGroupId: '' })).toEqual({ type: 'standard' })
  })
  it('parses the union back to a draft', () => {
    expect(parseProductConfiguration({ __typename: 'ClothingProductConfiguration', sizes: ['S', 'M'] }))
      .toMatchObject({ type: 'clothing', sizes: 'S, M' })
    expect(parseProductConfiguration({ __typename: 'SubscriptionProductConfiguration', planGroupId: 'pg' }))
      .toMatchObject({ type: 'subscription', planGroupId: 'pg' })
  })
})

describe('CatalogProductExtras', () => {
  it('omits null min/max and round-trips quantity requirements', () => {
    expect(buildCatalogExtras({ type: 'quantityRequirements', min: 1, max: 5 })).toEqual({ type: 'quantityRequirements', min: 1, max: 5 })
    expect(buildCatalogExtras({ type: 'quantityRequirements', min: null, max: null })).toEqual({ type: 'quantityRequirements' })
    expect(buildCatalogExtras({ type: 'none', min: null, max: null })).toEqual({ type: 'none' })
    expect(parseCatalogExtras({ __typename: 'QuantityRequirements', min: 2, max: null })).toEqual({ type: 'quantityRequirements', min: 2, max: null })
  })
})

describe('fulfillmentStage', () => {
  it('picks the most-advanced fulfillment flag present', () => {
    expect(fulfillmentStage(['PAID', 'PREPARING'])).toBe('PREPARING')
    expect(fulfillmentStage(['PAID', 'PREPARING', 'SHIPPING'])).toBe('SHIPPING')
    expect(fulfillmentStage(['PAID', 'SHIPPED', 'COMPLETE'])).toBe('COMPLETE')
    expect(fulfillmentStage(['PAID', 'SHIPPED'])).toBe('SHIPPED')
  })
  it('falls back to PAID when no fulfillment flag is set', () => {
    expect(fulfillmentStage(['PAID', 'PAYMENT_DUE'])).toBe('PAID')
    expect(fulfillmentStage([])).toBe('PAID')
  })
})

describe('dimension & weight unit abbreviations', () => {
  it('maps length units to suffixes, defaulting to inches', () => {
    expect(lengthUnitAbbr('INCHES')).toBe('in')
    expect(lengthUnitAbbr('CENTIMETERS')).toBe('cm')
    expect(lengthUnitAbbr(null)).toBe('in')
    expect(lengthUnitAbbr(undefined)).toBe('in')
    expect(lengthUnitAbbr('')).toBe('in')
  })
  it('maps weight units to suffixes, defaulting to pounds', () => {
    expect(weightUnitAbbr('POUNDS')).toBe('lb')
    expect(weightUnitAbbr('KILOGRAMS')).toBe('kg')
    expect(weightUnitAbbr(null)).toBe('lb')
    expect(weightUnitAbbr(undefined)).toBe('lb')
    expect(weightUnitAbbr('')).toBe('lb')
  })
})

describe('misc formatters', () => {
  it('buildPlanConfiguration always has a type', () => {
    expect(buildPlanConfiguration('standard')).toEqual({ type: 'standard' })
    expect(buildPlanConfiguration('')).toEqual({ type: 'standard' })
  })
  it('formatMoney renders the scale-4 string as currency', () => {
    expect(formatMoney('19.9900')).toBe('$19.99') // defaults to USD
    expect(formatMoney(null)).toBe('—')
    expect(formatMoney('abc')).toBe('abc')
  })
  it('formatMoney honors the ISO-4217 currency and falls back gracefully', () => {
    expect(formatMoney('19.9900', 'EUR')).toBe('€19.99')
    expect(formatMoney('5.00', 'GBP')).toBe('£5.00')
    expect(formatMoney('19.9900', 'usd')).toBe('$19.99') // case-insensitive
    expect(formatMoney('5.00', null)).toBe('$5.00')          // null -> USD default
    expect(formatMoney('5.00', 'BADCODE')).toBe('BADCODE 5.00') // malformed code -> plain fallback (Intl throws)
  })
  it('formatInterval pluralizes', () => {
    expect(formatInterval(1, 'MONTHS')).toBe('every 1 month')
    expect(formatInterval(3, 'MONTHS')).toBe('every 3 months')
    expect(formatInterval(1, 'DAYS')).toBe('every 1 day')
  })
  it('datetime-local <-> ISO conversions handle blanks and bad input', () => {
    expect(isoToLocalInput('2026-06-14T10:30:00.000Z')).toBe('2026-06-14T10:30')
    expect(isoToLocalInput('')).toBe('')
    expect(isoToLocalInput('not-a-date')).toBe('')
    expect(localInputToIso('')).toBeNull()
    expect(localInputToIso('bad')).toBeNull()
    expect(localInputToIso('2026-06-14T10:30')).toMatch(/Z$/)
  })
})
