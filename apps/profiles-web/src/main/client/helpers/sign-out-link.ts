import type { ClientContext } from "@bosca/bml"

/** Clears a revoked local session before following the user-selected destination. */
export async function signOutAndNavigate(href: string, active: () => boolean) {
  try {
    await auth.signOut()
  } catch (error) {
    console.warn("profiles: remote sign-out failed", error)
  } finally {
    auth.destroy()
    if (active()) location.assign(href)
  }
}

export function signOutLink(ctx: ClientContext) {
  const link = ctx.root as HTMLAnchorElement
  let pending = false, disposed = false
  ctx.on(link, "click", event => {
    event.preventDefault()
    if (pending) return
    pending = true
    link.setAttribute("aria-disabled", "true")
    void signOutAndNavigate(link.href, () => !disposed)
  })
  ctx.onUnmount(() => { disposed = true })
}
