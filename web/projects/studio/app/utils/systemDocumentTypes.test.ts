import { describe, it, expect } from 'vitest'
import { isSystemDocumentType } from './systemDocumentTypes'

describe('isSystemDocumentType', () => {
  it('matches WorkOps and Feed Item types', () => {
    expect(isSystemDocumentType('WorkOps Spec')).toBe(true)
    expect(isSystemDocumentType('WorkOps Requirement')).toBe(true)
    expect(isSystemDocumentType('Feed Item')).toBe(true)
  })

  it('leaves CMS types and non-strings alone', () => {
    expect(isSystemDocumentType('Article')).toBe(false)
    expect(isSystemDocumentType('WorkOps')).toBe(false)
    expect(isSystemDocumentType(null)).toBe(false)
    expect(isSystemDocumentType(undefined)).toBe(false)
  })
})
