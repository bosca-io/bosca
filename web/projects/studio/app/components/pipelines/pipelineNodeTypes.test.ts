import { describe, expect, it } from 'vitest'
import { collectCastTypes, inputNodeDeclaredOutput, type BrowsableType } from './pipelineNodeTypes'

describe('inputNodeDeclaredOutput', () => {
  it('declares a concrete or interface input as its exact object type', () => {
    expect(inputNodeDeclaredOutput('bosca.events.ContentEvent')).toEqual({
      kind: 'OBJECT',
      type: 'bosca.events.ContentEvent',
    })
  })

  it('declares an inline shape as an anonymous object', () => {
    expect(inputNodeDeclaredOutput('SHAPE')).toEqual({ kind: 'OBJECT', type: null })
  })

  it('leaves JSON and blank inputs untyped', () => {
    expect(inputNodeDeclaredOutput('JSON')).toEqual({ kind: null, type: null })
    expect(inputNodeDeclaredOutput('')).toEqual({ kind: null, type: null })
  })
})

describe('collectCastTypes', () => {
  it('adds catalogued interfaces that have no browsable field structure', () => {
    const structured: BrowsableType[] = [{
      name: 'bosca.events.ContentAdded',
      shortName: 'ContentAdded',
      fields: [{ name: 'id', type: 'UUID' }],
    }]

    const types = collectCastTypes(
      ['bosca.events.ContentEvent', 'bosca.events.ContentAdded'],
      structured,
    )

    expect(types.map(t => t.name)).toEqual([
      'bosca.events.ContentAdded',
      'bosca.events.ContentEvent',
    ])
    expect(types[0]?.fields).toEqual([{ name: 'id', type: 'UUID' }])
    expect(types[1]?.fields).toEqual([])
  })

  it('trims, deduplicates, and ignores blank catalog entries', () => {
    expect(collectCastTypes([' bosca.Event ', 'bosca.Event', ''], [])).toEqual([{
      name: 'bosca.Event',
      shortName: 'Event',
      fields: [],
    }])
  })
})
