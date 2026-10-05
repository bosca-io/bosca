import { describe, it, expect } from 'vitest'
import { getViewportInfo } from './viewport'

describe('getViewportInfo', () => {
  it('should return real dimensions in browser or zeros in SSR', () => {
    const info = getViewportInfo()

    if (typeof window !== 'undefined') {
      // Browser environment — should return real values
      expect(info.viewportWidth).toBeGreaterThan(0)
      expect(info.viewportHeight).toBeGreaterThan(0)
    } else {
      // SSR / node — fallback zeros
      expect(info.viewportWidth).toBe(0)
      expect(info.viewportHeight).toBe(0)
    }

    expect(info.scrollX).toBeTypeOf('number')
    expect(info.scrollY).toBeTypeOf('number')
  })
})
