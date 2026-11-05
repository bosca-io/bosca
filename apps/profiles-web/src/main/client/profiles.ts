/// <reference lib="es2020" />
/// <reference lib="dom" />

import { BoscaAuth } from "@bosca/auth-client-browser/core"

interface ProfilesWebConfig {
  cookieDomain?: string
}

interface BmlRuntimeConfig {
  authCookiePrefix?: string
}

const auth = new BoscaAuth({
  apiUrl: "",
  storage: "cookie",
  tokenName: globalThis.bmlConfig?.authCookiePrefix,
  cookieDomain: globalThis.profilesWebConfig?.cookieDomain,
})

declare global {
  var auth: BoscaAuth
  var bmlConfig: BmlRuntimeConfig | undefined
  var profilesWebConfig: ProfilesWebConfig | undefined
  var profilesReturnPath: (requested: string | null) => string
}

window.auth = auth

function profilesReturnPath(requested: string | null) {
  if (!requested?.startsWith("/")) return "/"
  try {
    const target = new URL(requested, location.origin)
    if (target.origin === location.origin) {
      return `${target.pathname}${target.search}${target.hash}`
    }
  } catch {
    // Keep the account overview as the safe destination.
  }
  return "/"
}

window.profilesReturnPath = profilesReturnPath

async function initializeProfilesAuth(auth: BoscaAuth) {
  try {
    await auth.initialize(location.pathname === "/login")
  } catch (error) {
    console.warn("profiles: session restore failed", error)
  }

  const redirectResult = await auth.handleRedirectResult().catch((error) => {
    console.warn("profiles: OAuth exchange failed", error)
    return null
  })
  if (redirectResult) {
    const requested = new URLSearchParams(location.search).get("redirect")
    location.replace(profilesReturnPath(requested))
    return
  }
}

void initializeProfilesAuth(auth).catch((error) => console.warn("profiles: authentication initialization failed", error))
