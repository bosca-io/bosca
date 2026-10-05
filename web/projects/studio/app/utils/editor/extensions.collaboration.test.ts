import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as Y from 'yjs'
import type { Metadata, Profile } from '~/types/graphql'
import { newExtensions } from './extensions'

const state = vi.hoisted(() => ({
  getToken: vi.fn(),
  authListeners: new Map<string, () => void>(),
  providers: [] as Array<{
    configuration: { token: () => Promise<string>, name: string },
    sendToken: ReturnType<typeof vi.fn>,
    destroy: ReturnType<typeof vi.fn>
  }>,
  unmount: undefined as (() => void) | undefined
}))

vi.mock('@bosca/auth-client-browser', () => ({
  useAuth: () => ({ auth: {
    getToken: state.getToken,
    on: (event: string, callback: () => void) => {
      state.authListeners.set(event, callback)
      return () => { state.authListeners.delete(event) }
    }
  } })
}))
vi.mock('@hocuspocus/provider', () => ({
  HocuspocusProvider: class {
    configuration: { token: () => Promise<string>, name: string }
    onDestroy?: () => void
    sendToken = vi.fn()
    destroy = vi.fn(() => this.onDestroy?.())
    constructor(configuration: { token: () => Promise<string>, name: string }) {
      this.configuration = configuration
      state.providers.push(this)
    }
    on(event: string, callback: () => void) {
      if (event === 'destroy') this.onDestroy = callback
    }
  }
}))

describe('editor collaboration authentication', () => {
  let doc: Y.Doc
  beforeEach(() => {
    state.authListeners.clear()
    state.providers.length = 0
    state.getToken.mockReset().mockResolvedValue('current-token')
    vi.stubGlobal('onBeforeUnmount', (callback: () => void) => { state.unmount = callback })
    doc = new Y.Doc()
    newExtensions(
      { id: '11111111-1111-4111-8111-111111111111', version: 1 } as Metadata,
      { name: 'Editor' } as Profile,
      doc
    )
  })
  afterEach(() => {
    state.unmount?.()
    doc.destroy()
  })

  it('uses the auth client token for each join and resends refreshed credentials', async () => {
    const provider = state.providers[0]!
    expect(await provider.configuration.token()).toBe('current-token')
    state.getToken.mockResolvedValue('refreshed-token')
    state.authListeners.get('tokenRefreshed')?.()
    expect(provider.sendToken).toHaveBeenCalledOnce()
    expect(await provider.configuration.token()).toBe('refreshed-token')
    state.getToken.mockResolvedValue(null)
    expect(await provider.configuration.token()).toBe('')
  })

  it('destroys collaboration on sign-out and removes auth listeners', () => {
    const provider = state.providers[0]!
    state.authListeners.get('signedOut')?.()
    expect(provider.destroy).toHaveBeenCalledOnce()
    expect(state.authListeners.size).toBe(0)
  })

  it('removes the connection and auth listeners when the editor unmounts', () => {
    const provider = state.providers[0]!
    state.unmount?.()
    expect(provider.destroy).toHaveBeenCalledOnce()
    expect(state.authListeners.size).toBe(0)
  })
})
