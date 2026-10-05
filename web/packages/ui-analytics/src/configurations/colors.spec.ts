import { describe, it, expect } from 'vitest'
import { makePalette } from './colors'

describe('makePalette', () => {
  it('returns single default color for count <= 1', () => {
    const result = makePalette(1)
    expect(result).toHaveLength(1)
    expect(result[0]).toBe('#3b9eff')
  })

  it('returns single accent color when provided', () => {
    const result = makePalette(1, '#ff0000')
    expect(result).toEqual(['#ff0000'])
  })

  it('returns count 0 as single default color', () => {
    expect(makePalette(0)).toEqual(['#3b9eff'])
  })

  it('uses accent as first color in multi-color palette', () => {
    const result = makePalette(3, '#custom')
    expect(result[0]).toBe('#custom')
    expect(result).toHaveLength(3)
  })

  it('returns correct number of colors', () => {
    expect(makePalette(5)).toHaveLength(5)
    expect(makePalette(10)).toHaveLength(10)
  })

  it('wraps around the palette for counts exceeding palette size', () => {
    const result = makePalette(12)
    expect(result).toHaveLength(12)
    expect(result[10]).toBe(result[0])
  })
})
