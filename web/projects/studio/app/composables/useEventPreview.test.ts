import { describe, it, expect } from 'vitest'
import { getEventPreview } from './useEventPreview'
import type { TimeEvent, TimeEventType } from './useTimeEvents'

function makeType(attrs: { key: string; type: string; ui?: string }[]): TimeEventType {
  return {
    id: 'test-type',
    name: 'Test',
    description: '',
    schema: null,
    configuration: {},
    attributes: attrs.map((a) => ({
      key: a.key,
      name: a.key,
      description: '',
      type: a.type,
      ui: a.ui ?? 'INPUT',
      list: false,
      configuration: null,
      supplementaryKey: null,
    })),
  }
}

function makeEvent(
  typeAttrs: { key: string; type: string; ui?: string }[],
  attributes: Record<string, unknown>,
): TimeEvent {
  return {
    id: 'evt-1',
    metadataId: 'meta-1',
    metadataVersion: 1,
    type: makeType(typeAttrs),
    startOffsetMs: 0,
    endOffsetMs: null,
    sort: 0,
    attributes,
    created: '',
    modified: '',
    durationMs: null,
  }
}

describe('getEventPreview', () => {
  it('extracts image preview from METADATA attribute (string ID)', () => {
    const event = makeEvent(
      [{ key: 'slide', type: 'METADATA' }],
      { slide: 'img-123' },
    )
    const result = getEventPreview(event)
    expect(result).toEqual({ type: 'image', src: '/content/image/img-123' })
  })

  it('extracts image preview from METADATA attribute (object with jpeg key)', () => {
    const event = makeEvent(
      [{ key: 'slide', type: 'METADATA' }],
      { slide: { id: 'img-123', attributes: { jpeg: { large: 'hash456' } } } },
    )
    const result = getEventPreview(event)
    expect(result).toEqual({ type: 'image', src: '/content/image/img-123?key=hash456' })
  })

  it('extracts image preview from attribute with IMAGE ui', () => {
    const event = makeEvent(
      [{ key: 'photo', type: 'STRING', ui: 'IMAGE' }],
      { photo: 'img-789' },
    )
    const result = getEventPreview(event)
    expect(result).toEqual({ type: 'image', src: '/content/image/img-789' })
  })

  it('falls back to STRING attribute for text preview', () => {
    const event = makeEvent(
      [{ key: 'title', type: 'STRING' }],
      { title: 'Hello World' },
    )
    const result = getEventPreview(event)
    expect(result).toEqual({ type: 'text', text: 'Hello World' })
  })

  it('prioritizes IMAGE over STRING', () => {
    const event = makeEvent(
      [
        { key: 'slide', type: 'METADATA' },
        { key: 'title', type: 'STRING' },
      ],
      { slide: 'img-1', title: 'Some Title' },
    )
    const result = getEventPreview(event)
    expect(result?.type).toBe('image')
  })

  it('returns null when no preview available', () => {
    const event = makeEvent(
      [{ key: 'count', type: 'INT' }],
      { count: 42 },
    )
    expect(getEventPreview(event)).toBeNull()
  })

  it('returns null for null attributes', () => {
    const event = makeEvent([], {})
    event.attributes = null
    expect(getEventPreview(event)).toBeNull()
  })

  it('skips empty STRING values', () => {
    const event = makeEvent(
      [{ key: 'title', type: 'STRING' }],
      { title: '' },
    )
    expect(getEventPreview(event)).toBeNull()
  })

  it('skips null METADATA values', () => {
    const event = makeEvent(
      [{ key: 'slide', type: 'METADATA' }, { key: 'title', type: 'STRING' }],
      { slide: null, title: 'Fallback' },
    )
    const result = getEventPreview(event)
    expect(result).toEqual({ type: 'text', text: 'Fallback' })
  })
})
