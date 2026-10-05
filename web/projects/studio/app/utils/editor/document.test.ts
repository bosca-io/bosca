import { describe, expect, it } from 'vitest'
import { normalizeDocumentReferences, sanitizeDocument } from './document'

describe('document sanitization', () => {
  it('keeps only non-empty string references', () => {
    expect(normalizeDocumentReferences([
      'MAT.6.22+MAT.6.23+MAT.6.24',
      null,
      undefined,
      '',
      6,
      'MAT.6.33+MAT.6.34',
    ])).toEqual([
      'MAT.6.22+MAT.6.23+MAT.6.24',
      'MAT.6.33+MAT.6.34',
    ])
  })

  it('removes invalid references from a contentless container before serialization', () => {
    const document = {
      type: 'doc',
      content: [{
        type: 'container',
        attrs: {
          name: 'BIBLE_REFERENCES',
          metadataId: '8916c1e6-b3d3-4c95-b2a8-1ee3f81f0ece',
          references: ['MAT.6.24', null, 'MAT.6.33'],
        },
      }],
    }

    expect(sanitizeDocument(document)).toEqual({
      type: 'doc',
      content: [{
        type: 'container',
        attrs: {
          name: 'BIBLE_REFERENCES',
          metadataId: '8916c1e6-b3d3-4c95-b2a8-1ee3f81f0ece',
          references: ['MAT.6.24', 'MAT.6.33'],
        },
      }],
    })
  })

  it.each([
    'MAT.6.24',
    6,
    { usfm: 'MAT.6.24' },
  ])('replaces malformed non-array references before serialization', (references) => {
    const document = {
      type: 'container',
      attrs: { name: 'BIBLE_REFERENCES', references },
    }

    expect(sanitizeDocument(document)).toEqual({
      type: 'container',
      attrs: { name: 'BIBLE_REFERENCES', references: [] },
    })
  })

  it('preserves nullable reference attributes and unrelated node data', () => {
    const document = {
      type: 'container',
      attrs: { name: 'MAIN_CONTENT', references: null },
      content: [{ type: 'paragraph', attrs: { textAlign: 'center' } }],
    }

    expect(sanitizeDocument(document)).toEqual(document)
  })
})
