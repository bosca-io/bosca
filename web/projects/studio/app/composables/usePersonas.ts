import gql from 'graphql-tag'
import { SUBSYSTEMS } from '~/composables/useSubsystems'
import type { ServerFeatureFlags } from '~/composables/useServerFeatures'
import type { Subsystem } from '~~/shared/types'

export interface Persona {
  id: string
  name: string
  subsystemIds: string[]
}

const ADMIN_GROUP = 'administrators'

/**
 * Resolves the current user's admin status and profile in a single request.
 * Uses GraphQL (rather than the client-only `useAuth()` reactive state) so it
 * runs on the server too: `useGraphQL` mints a Bearer header from the
 * SSR-seeded auth cookie, letting the persona decision be made during SSR
 * instead of after hydration.
 *
 * The profile id comes from `profiles.current` (the same source the auth
 * client's `currentProfile` uses), not the principal's `primaryProfileId` —
 * that field is nullable and frequently unset, which would drop a persona
 * assigned to the user's profile and wrongly route them to /welcome.
 */
const studioAccessGql = gql`
  query StudioAccess {
    security {
      principals {
        current {
          groups { name }
        }
      }
    }
    profiles {
      current {
        id
        isPrimary
      }
    }
  }
`

const personasGql = gql`
  query MyPersonas($profileId: UUID!) {
    profiles {
      studioPersonas {
        byProfile(profileId: $profileId) {
          id name subsystemIds
        }
      }
    }
  }
`

interface StudioAccess {
  isAdmin: boolean
  groups: string[]
  personas: Persona[]
}

async function fetchStudioAccess(
  // The nuxtApp + GraphQL client are captured by the caller (usePersonas) at
  // composable-invocation time and passed in. They must NOT be resolved here:
  // when this load runs alongside others in a `Promise.all`, an earlier load's
  // `runWithContext()` restores (clears) the Nuxt context before this function's
  // synchronous prefix runs, so a `useNuxtApp()` here throws "outside … setup".
  // `runWithContext` re-establishes the captured context around each query so
  // every call resolves the runtime apiUrl and auth header correctly.
  nuxtApp: ReturnType<typeof useNuxtApp>,
  query: ReturnType<typeof useGraphQL>['query'],
): Promise<StudioAccess> {
  const access = await nuxtApp.runWithContext(() => query<{
    security: { principals: { current: { groups: { name: string }[] } | null } }
    profiles: { current: { id: string; isPrimary: boolean }[] | null }
  }>(studioAccessGql))

  const groups = access?.security?.principals?.current?.groups ?? []
  const groupNames = groups.map(g => g.name)
  const isAdmin = groupNames.includes(ADMIN_GROUP)

  // Use the primary profile (falling back to the first) — the same profile the
  // auth client treats as current, and the one personas are assigned against.
  const profiles = access?.profiles?.current ?? []
  const profileId = profiles.find(p => p.isPrimary)?.id ?? profiles[0]?.id ?? null

  let personas: Persona[] = []
  if (profileId) {
    try {
      const result = await nuxtApp.runWithContext(() => query<{
        profiles: { studioPersonas: { byProfile: Persona[] } }
      }>(personasGql, { profileId }))
      personas = result?.profiles?.studioPersonas?.byProfile ?? []
    } catch (err) {
      // A failure resolving personas must not discard the admin status we
      // already determined above. Admins get every subsystem without a
      // persona, so an empty list still grants them full access; a non-admin
      // simply falls through to /welcome, the correct fallback. Coupling the
      // two queries previously let a transient personas-query error route an
      // admin to /welcome.
      console.error('Failed to load studio personas:', err)
    }
  }

  return { isAdmin, groups: groupNames, personas }
}

/**
 * In-flight load promise — module-level and CLIENT-ONLY. Deliberately NOT a
 * `useState`: Nuxt serializes every `useState` into the SSR payload and devalue
 * cannot stringify a Promise (it throws "Cannot stringify a Promise"). On the
 * server load() is awaited once per request and guarded by `loaded` (no
 * concurrent loads), so no in-flight holder is needed there; on the client this
 * collapses the eager loads fired by multiple components during hydration into a
 * single request. Only the resolved data and the `loaded` flag live in
 * serializable `useState`.
 */
let clientLoad: Promise<void> | null = null

export function usePersonas() {
  const personas = useState<Persona[]>('personas', () => [])
  const isAdmin = useState<boolean>('personas:admin', () => false)
  const groupNames = useState<string[]>('personas:groups', () => [])
  const loaded = useState<boolean>('personas:loaded', () => false)

  // Capture the Nuxt app + GraphQL client now (valid context), not inside load()
  // — see the note on fetchStudioAccess for why resolving them in load() breaks
  // under Promise.all.
  const nuxtApp = useNuxtApp()
  const { query } = useGraphQL()

  /**
   * Resolves persona/admin access once and caches it in shared state.
   * Idempotent: concurrent callers await the same in-flight promise, and an
   * already-resolved result (including one hydrated from the SSR payload) is
   * reused without re-fetching. The route middleware and index page `await`
   * this so the `/welcome` decision is made during SSR.
   */
  function load(): Promise<void> {
    if (loaded.value) return Promise.resolve()
    if (import.meta.client && clientLoad) return clientLoad
    const promise = (async () => {
      try {
        const access = await fetchStudioAccess(nuxtApp, query)
        isAdmin.value = access.isAdmin
        groupNames.value = access.groups
        if (access.personas.length > 0) {
          personas.value = access.personas
        }
        // Only a successful lookup is authoritative. Marking `loaded` inside
        // `try` (rather than `finally`) is deliberate: see the catch below.
        loaded.value = true
      } catch (err) {
        // The access query itself failed (e.g. an SSR request that reached the
        // backend without a usable auth header, or a transient network error).
        // Surface it rather than swallowing — and crucially, do NOT mark the
        // result `loaded`: a failed lookup must not be cached as an
        // authoritative "no access" answer. Leaving `loaded` false lets the
        // client re-resolve after hydration (where the auth token is reliably
        // present), so a transient failure can't strand an admin on /welcome.
        console.error('Failed to resolve studio access:', err)
      } finally {
        if (import.meta.client) clientLoad = null
      }
    })()
    if (import.meta.client) clientLoad = promise
    return promise
  }

  // Eagerly resolve on the client for components that read `isAdmin` /
  // `personas` reactively (the welcome page, the sidebar nav). On the server,
  // callers that gate routing `await load()` explicitly so the result lands in
  // the SSR payload and the client reuses it without re-fetching.
  if (import.meta.client && !loaded.value && !clientLoad) {
    void load()
  }

  const ready = computed(() => loaded.value)

  const hasPersonas = computed(() => personas.value.length > 0)

  // An admin with no assigned persona gets access to every subsystem, so they
  // drop straight into the app rather than being asked which to enable.
  const allSubsystems = computed(() => isAdmin.value && !hasPersonas.value)

  const allowedSubsystemIds = computed(() => {
    if (allSubsystems.value) return new Set(SUBSYSTEMS.map(s => s.id))
    const ids = new Set<string>()
    for (const p of personas.value) {
      for (const id of p.subsystemIds) ids.add(id)
    }
    return ids
  })

  const allowedSubsystems = computed<Subsystem[]>(() => {
    if (allSubsystems.value) return SUBSYSTEMS
    const ids = allowedSubsystemIds.value
    if (ids.size === 0) return []
    return SUBSYSTEMS.filter(s => ids.has(s.id))
  })

  const { isEnabled: isFeatureEnabled } = useServerFeatures()

  function subsystemFeatureEnabled(s: Subsystem): boolean {
    return !s.requiresFeature || isFeatureEnabled(s.requiresFeature as keyof ServerFeatureFlags)
  }

  /**
   * The subsystems the current user should actually see: the persona/admin
   * resolved set, minus any whose required server feature is disabled.
   *
   * The base mirrors what the nav previously computed inline — admins (and the
   * no-persona fallback) get the full catalog; everyone else gets their persona
   * subsystems. The feature filter then drops turned-off modules (e.g. commerce
   * when ecommerce is disabled) for *every* path, including admins, so a
   * disabled module never appears in navigation.
   */
  const visibleSubsystems = computed<Subsystem[]>(() => {
    const base = (isAdmin.value || allowedSubsystems.value.length === 0) ? SUBSYSTEMS : allowedSubsystems.value
    return base.filter(subsystemFeatureEnabled)
  })

  const visibleSubsystemIds = computed(() => new Set(visibleSubsystems.value.map(s => s.id)))

  /** True if the current user is in [name] (any of the user's resolved groups). */
  function hasGroup(name: string): boolean {
    return groupNames.value.includes(name)
  }

  return { personas, allowedSubsystems, allowedSubsystemIds, visibleSubsystems, visibleSubsystemIds, hasPersonas, isAdmin, groupNames, hasGroup, ready, load }
}
