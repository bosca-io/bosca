import { describe, expect, it } from 'vitest'
import { promotionChains, promotionOrder } from './promotionOrder'

interface Env { id: string; name: string; displayOrder: number; promotionSourceIds?: string[] }
const env = (id: string, displayOrder: number, promotionSourceIds: string[] = []): Env =>
  ({ id, name: id, displayOrder, promotionSourceIds })

describe('promotionChains', () => {
  it('orders a single family by its promotion edges, not displayOrder', () => {
    // displayOrder deliberately contradicts the promotion flow — the graph must win.
    const envs = [env('production', 0, ['staging']), env('development', 2), env('staging', 1, ['development'])]
    expect(promotionChains(envs).map(c => c.map(e => e.id))).toEqual([['development', 'staging', 'production']])
  })

  it('keeps disjoint promotion families as separate chains', () => {
    const envs = [
      env('play-internal', 3), env('play-production', 4, ['play-internal']),
      env('development', 0), env('staging', 1, ['development']), env('production', 2, ['staging']),
      env('testflight', 5), env('app-store', 6, ['testflight']),
    ]
    expect(promotionChains(envs).map(c => c.map(e => e.id))).toEqual([
      ['development', 'staging', 'production'],
      ['play-internal', 'play-production'],
      ['testflight', 'app-store'],
    ])
  })

  it('orders chains by their smallest displayOrder', () => {
    const envs = [env('b1', 5), env('b2', 6, ['b1']), env('a1', 1), env('a2', 9, ['a1'])]
    expect(promotionChains(envs).map(c => c.map(e => e.id))).toEqual([['a1', 'a2'], ['b1', 'b2']])
  })

  it('treats environments without promotion edges as single-stop chains, by displayOrder', () => {
    const envs = [env('preview', 2), env('development', 1)]
    expect(promotionChains(envs).map(c => c.map(e => e.id))).toEqual([['development'], ['preview']])
  })

  it('a many-to-many merge point sorts after every source', () => {
    const envs = [env('production', 3, ['staging', 'canary']), env('staging', 1, ['development']), env('canary', 2, ['development']), env('development', 0)]
    expect(promotionChains(envs).map(c => c.map(e => e.id))).toEqual([['development', 'staging', 'canary', 'production']])
  })

  it('appends cycle members instead of dropping them', () => {
    const envs = [env('a', 0, ['b']), env('b', 1, ['a']), env('entry', 2)]
    const ids = promotionChains(envs).flat().map(e => e.id)
    expect(ids.sort()).toEqual(['a', 'b', 'entry'])
  })

  it('ignores promotion sources pointing outside the program', () => {
    const envs = [env('development', 0, ['gone'])]
    expect(promotionChains(envs).map(c => c.map(e => e.id))).toEqual([['development']])
  })
})

describe('promotionOrder', () => {
  it('flattens the chains, family by family', () => {
    const envs = [env('play', 2), env('development', 0), env('staging', 1, ['development'])]
    expect(promotionOrder(envs).map(e => e.id)).toEqual(['development', 'staging', 'play'])
  })

  it('is empty for no environments', () => {
    expect(promotionOrder([])).toEqual([])
  })
})
