import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'
// Importing only defines the composable; it reads `useState`/`useGraphQL`
// lazily at call time, so the stubs below are in place before any test runs.
import { usePersonas } from './usePersonas'

// usePersonas relies on Nuxt's `useState` (shared, keyed state) and the app's
// `useGraphQL` composable. Back `useState` with a per-key ref store and make
// the GraphQL query controllable per test.
const stateStore = new Map<string, { value: unknown }>()
vi.stubGlobal('useState', <T>(key: string, init: () => T) => {
  if (!stateStore.has(key)) stateStore.set(key, ref(init()) as { value: unknown })
  return stateStore.get(key)
})

const query = vi.fn()
vi.stubGlobal('useGraphQL', () => ({ query }))

// fetchStudioAccess restores the Nuxt instance context around each query via
// `runWithContext` (so the SSR personas query survives the await boundary that
// otherwise tears the context down). There is no real context under test — just
// invoke the callback so the mocked `query` runs unchanged.
vi.stubGlobal('useNuxtApp', () => ({ runWithContext: <T>(fn: () => T) => fn() }))

// usePersonas reads server feature flags to drop subsystems whose module is
// disabled (visibleSubsystems). Back it with a controllable per-feature map;
// default everything enabled so the persona/admin assertions are unaffected.
const featureEnabled: Record<string, boolean> = { ecommerce: true }
vi.stubGlobal('useServerFeatures', () => ({
  isEnabled: (f: string) => featureEnabled[f] ?? true,
}))

const ADMIN_ACCESS = {
  security: { principals: { current: { groups: [{ name: 'administrators' }] } } },
  profiles: { current: [{ id: 'profile-1', isPrimary: true }] },
}
const NON_ADMIN_ACCESS = {
  security: { principals: { current: { groups: [{ name: 'editors' }] } } },
  profiles: { current: [{ id: 'profile-1', isPrimary: true }] },
}
const NO_PERSONAS = { profiles: { studioPersonas: { byProfile: [] } } }

let errorSpy: ReturnType<typeof vi.spyOn>

beforeEach(() => {
  stateStore.clear()
  query.mockReset()
  featureEnabled.ecommerce = true
  errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
})

describe('usePersonas().load()', () => {
  it('resolves admin access from the administrators group', async () => {
    query.mockResolvedValueOnce(ADMIN_ACCESS).mockResolvedValueOnce(NO_PERSONAS)

    const personas = usePersonas()
    await personas.load()

    expect(personas.isAdmin.value).toBe(true)
    expect(personas.ready.value).toBe(true)
    // An admin with no persona gets every subsystem.
    expect(personas.allowedSubsystems.value.length).toBeGreaterThan(0)
  })

  it('keeps an admin authorized even when the personas query fails', async () => {
    // The personas sub-query throwing must NOT discard the admin status
    // already resolved by the access query (the bug that flashed /welcome).
    query
      .mockResolvedValueOnce(ADMIN_ACCESS)
      .mockRejectedValueOnce(new Error('personas backend down'))

    const personas = usePersonas()
    await personas.load()

    expect(personas.isAdmin.value).toBe(true)
    expect(personas.ready.value).toBe(true)
    expect(personas.hasPersonas.value).toBe(false)
    expect(errorSpy).toHaveBeenCalledWith('Failed to load studio personas:', expect.any(Error))
  })

  it('does not cache a failed access lookup as "no access"', async () => {
    // A failure resolving access (e.g. an SSR request with no usable auth
    // header) must be surfaced and left un-cached so the client can retry —
    // not silently treated as a confirmed absence of access.
    query.mockRejectedValueOnce(new Error('no auth header'))

    const personas = usePersonas()
    await personas.load()

    expect(personas.isAdmin.value).toBe(false)
    expect(personas.ready.value).toBe(false)
    expect(errorSpy).toHaveBeenCalledWith('Failed to resolve studio access:', expect.any(Error))

    // A subsequent load re-resolves rather than returning the cached failure.
    query.mockResolvedValueOnce(ADMIN_ACCESS).mockResolvedValueOnce(NO_PERSONAS)
    await personas.load()

    expect(personas.isAdmin.value).toBe(true)
    expect(personas.ready.value).toBe(true)
  })

  it('grants a non-admin only the subsystems from their personas', async () => {
    query.mockResolvedValueOnce(NON_ADMIN_ACCESS).mockResolvedValueOnce({
      profiles: {
        studioPersonas: {
          byProfile: [{ id: 'p', name: 'Analyst', subsystemIds: ['analytics'] }],
        },
      },
    })

    const personas = usePersonas()
    await personas.load()

    expect(personas.isAdmin.value).toBe(false)
    expect(personas.hasPersonas.value).toBe(true)
    expect(personas.allowedSubsystems.value.map(s => s.id)).toEqual(['analytics'])
  })

  it('resolves a genuine no-access user (non-admin, no personas)', async () => {
    query.mockResolvedValueOnce(NON_ADMIN_ACCESS).mockResolvedValueOnce(NO_PERSONAS)

    const personas = usePersonas()
    await personas.load()

    expect(personas.isAdmin.value).toBe(false)
    expect(personas.hasPersonas.value).toBe(false)
    expect(personas.ready.value).toBe(true)
  })
})

describe('usePersonas().visibleSubsystems', () => {
  it('hides a feature-gated subsystem when its server feature is disabled', async () => {
    query.mockResolvedValueOnce(ADMIN_ACCESS).mockResolvedValueOnce(NO_PERSONAS)
    featureEnabled.ecommerce = false

    const personas = usePersonas()
    await personas.load()

    const visibleIds = personas.visibleSubsystems.value.map(s => s.id)
    // commerce requires the ecommerce feature, so it is dropped — even for an
    // admin, who still sees it in the raw persona-resolved allowedSubsystems.
    expect(visibleIds).not.toContain('commerce')
    expect(personas.visibleSubsystemIds.value.has('commerce')).toBe(false)
    expect(personas.allowedSubsystems.value.map(s => s.id)).toContain('commerce')
    // Non-gated subsystems remain visible.
    expect(visibleIds).toContain('cms')
  })

  it('shows a feature-gated subsystem when its server feature is enabled', async () => {
    query.mockResolvedValueOnce(ADMIN_ACCESS).mockResolvedValueOnce(NO_PERSONAS)
    featureEnabled.ecommerce = true

    const personas = usePersonas()
    await personas.load()

    expect(personas.visibleSubsystems.value.map(s => s.id)).toContain('commerce')
    expect(personas.visibleSubsystemIds.value.has('commerce')).toBe(true)
  })
})
