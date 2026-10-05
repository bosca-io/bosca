import { installDeferredIdentityProvider } from "./deferred-runtime"
import { authHeaders } from "./graphql"
import { sharedState } from "./shared-state"

export const DEFERRED_IDENTITY_URL = "/_bml/identity"

interface DeferredIdentityState {
  bootstrap: Promise<void> | null
  scheduled: boolean
  /**
   * True once a bootstrap succeeded for the current identity revision. The server has then issued
   * both identities; if the browser did not persist the cookies, asking again cannot fix that, so
   * later requests proceed instead of paying one extra bootstrap round trip each.
   */
  established: boolean
}

const state = sharedState<DeferredIdentityState>("@bosca/bml/deferred-identity", () => ({
  bootstrap: null,
  scheduled: false,
  established: false,
}))

/** Installs the shared request gate and starts identity setup after application configuration runs. */
export function enableDeferredIdentity(): void {
  installDeferredIdentityProvider(requiredIdentityBootstrap)
  if (state.scheduled || !requiresIdentityBootstrap()) return
  state.scheduled = true
  queueMicrotask(() => {
    state.scheduled = false
    void ensureDeferredIdentity().catch(() => {
      // A deferred boundary reports its own error; later private requests can retry.
    })
  })
}

export function advanceDeferredIdentityBootstrap(): void {
  state.bootstrap = null
  state.established = false
}

export async function ensureDeferredIdentity(): Promise<void> {
  await requiredIdentityBootstrap()
}

function requiredIdentityBootstrap(): Promise<void> | null {
  if (state.bootstrap) return state.bootstrap
  if (state.established || !requiresIdentityBootstrap()) return null
  const headers = authHeaders()
  const hasAnalyticsIdentity = Boolean(headers["X-BA-Session-ID"]?.trim() || hasCookie("bml_asid"))
  const needsInstallationIdentity = document.querySelector("[data-bml-installation-identity]") != null
  const hasInstallationIdentity = Boolean(headers["X-Installation-ID"]?.trim() || hasCookie("bml_iid"))
  if (hasAnalyticsIdentity && (!needsInstallationIdentity || hasInstallationIdentity)) return null
  let bootstrap!: Promise<void>
  bootstrap = fetch(DEFERRED_IDENTITY_URL, {
    method: "POST",
    headers: authHeaders({ Accept: "application/json" }),
    credentials: "same-origin",
    cache: "no-store",
  }).then((response) => {
    if (!response.ok) throw new Error(`BML identity bootstrap HTTP ${response.status}`)
    if (state.bootstrap === bootstrap) state.established = true
  }).finally(() => {
    if (state.bootstrap === bootstrap) state.bootstrap = null
  })
  state.bootstrap = bootstrap
  return bootstrap
}

function requiresIdentityBootstrap(): boolean {
  return typeof document !== "undefined" &&
    document.querySelector("[data-bml-deferred], [data-bml-shared-identity]") != null
}

function hasCookie(name: string): boolean {
  const prefix = `${name}=`
  return document.cookie.split(";").some((cookie) => {
    const value = cookie.trim()
    return value.startsWith(prefix) && value.length > prefix.length
  })
}
