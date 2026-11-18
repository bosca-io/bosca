import { describe, it, expect } from 'vitest'
import { compareTemplatesByOrder, sortFacetsByTemplateOrder, templateOrderByType, templateSortValue } from './templateOrder'

const t = (name: string, sort?: unknown) => ({
  name,
  attributes: sort === undefined ? null : { sort },
})

describe('templateSortValue', () => {
  it('reads a numeric sort attribute', () => {
    expect(templateSortValue(t('A', 3))).toBe(3)
    expect(templateSortValue(t('A', 0))).toBe(0)
  })

  it('treats missing or non-numeric values as unordered', () => {
    expect(templateSortValue(t('A'))).toBeNull()
    expect(templateSortValue(t('A', '2'))).toBeNull()
    expect(templateSortValue({ name: 'A', attributes: {} })).toBeNull()
  })
})

describe('compareTemplatesByOrder', () => {
  it('puts ordered templates first by position, then the rest A–Z', () => {
    const sorted = [t('Zeta'), t('Article', 1), t('Alpha'), t('Talk', 0), t('Video', 2)]
      .sort(compareTemplatesByOrder)
      .map(x => x.name)
    expect(sorted).toEqual(['Talk', 'Article', 'Video', 'Alpha', 'Zeta'])
  })

  it('breaks equal positions by name', () => {
    const sorted = [t('B', 1), t('A', 1)].sort(compareTemplatesByOrder).map(x => x.name)
    expect(sorted).toEqual(['A', 'B'])
  })

  it('falls back to A–Z when nothing is ordered', () => {
    const sorted = [t('Video'), t('Article'), t('Talk')].sort(compareTemplatesByOrder).map(x => x.name)
    expect(sorted).toEqual(['Article', 'Talk', 'Video'])
  })
})

describe('templateOrderByType', () => {
  it('keys positions by default type and by name, keeping the highest-placed', () => {
    const order = templateOrderByType([
      { name: 'Pages', attributes: { sort: 3 }, defaultAttributes: { type: 'Standalone Page' } },
      { name: 'Standalone Page', attributes: { sort: 14 }, defaultAttributes: { type: 'Standalone Page' } },
      { name: 'Daily Guide', attributes: { sort: 3 }, defaultAttributes: null },
      { name: 'Unordered', attributes: null, defaultAttributes: { type: 'Unordered' } },
    ])
    expect(order.get('Standalone Page')).toBe(3)
    expect(order.get('Pages')).toBe(3)
    expect(order.get('Daily Guide')).toBe(3)
    expect(order.has('Unordered')).toBe(false)
  })
})

describe('sortFacetsByTemplateOrder', () => {
  it('puts matched facets first by position and keeps the rest in their incoming order', () => {
    const facets = [
      { value: 'Study', count: 40 },
      { value: 'Talk', count: 30 },
      { value: 'Other', count: 20 },
      { value: 'Article', count: 10 },
      { value: 'Misc', count: 5 },
    ]
    const order = new Map([['Article', 0], ['Talk', 1]])
    expect(sortFacetsByTemplateOrder(facets, order).map(f => f.value)).toEqual(['Article', 'Talk', 'Study', 'Other', 'Misc'])
  })
})
