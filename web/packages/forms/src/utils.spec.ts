import { describe, it, expect } from 'vitest'
import { resolvePath } from './utils'

describe('resolvePath', () => {
  const obj = {
    name: 'Alice',
    address: {
      city: 'Portland',
      zip: '97201',
      geo: { lat: 45.5, lng: -122.6 },
    },
    tags: ['a', 'b'],
    empty: '',
    zero: 0,
    flag: false,
  }

  it('resolves a top-level property', () => {
    expect(resolvePath(obj, 'name')).toBe('Alice')
  })

  it('resolves a nested property', () => {
    expect(resolvePath(obj, 'address.city')).toBe('Portland')
  })

  it('resolves deeply nested properties', () => {
    expect(resolvePath(obj, 'address.geo.lat')).toBe(45.5)
  })

  it('returns undefined for a missing top-level key', () => {
    expect(resolvePath(obj, 'missing')).toBeUndefined()
  })

  it('returns undefined for a missing nested key', () => {
    expect(resolvePath(obj, 'address.street')).toBeUndefined()
  })

  it('returns undefined when traversing through a non-object', () => {
    expect(resolvePath(obj, 'name.length.foo')).toBeUndefined()
  })

  it('handles falsy values correctly (empty string)', () => {
    expect(resolvePath(obj, 'empty')).toBe('')
  })

  it('handles falsy values correctly (zero)', () => {
    expect(resolvePath(obj, 'zero')).toBe(0)
  })

  it('handles falsy values correctly (false)', () => {
    expect(resolvePath(obj, 'flag')).toBe(false)
  })

  it('resolves array values', () => {
    expect(resolvePath(obj, 'tags')).toEqual(['a', 'b'])
  })

  it('returns undefined when obj is null', () => {
    expect(resolvePath(null, 'foo')).toBeUndefined()
  })

  it('returns undefined when obj is undefined', () => {
    expect(resolvePath(undefined, 'foo')).toBeUndefined()
  })

  it('returns undefined when obj is a primitive', () => {
    expect(resolvePath(42, 'foo')).toBeUndefined()
  })
})
