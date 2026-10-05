/**
 * Sliver re-render: request/response, no websockets. An island POSTs its current state
 * to the server, which re-renders one component server-side and returns the HTML fragment; the island
 * swaps it in via `ctx.replace`. Same component code renders on first paint and on update, so the
 * markup stays consistent.
 *
 *   btn.addEventListener("click", async () => {
 *     count++
 *     ctx.replace("[data-view]", await renderFragment("counter-view", { count }))
 *   })
 */
import { authHeaders } from "./graphql"
import { currentPageLocale, pendingDeferredIdentity } from "./deferred-runtime"
import { stringifyJson } from "./json"

/** URL prefix the bml-server serves sliver re-renders under (same origin as the page). */
export const RENDER_PREFIX = "/_bml/render/"

/**
 * POST [props] as the component's state to the server and return the re-rendered HTML fragment.
 * Forwards the Bosca auth token (via [authHeaders]) so the component can fetch data on the server.
 */
export async function renderFragment(component: string, props: Record<string, unknown> = {}): Promise<string> {
  const identity = pendingDeferredIdentity()
  if (identity != null) await identity
  const locale = typeof document === "undefined" ? undefined : currentPageLocale()
  const url = `${RENDER_PREFIX}${encodeURIComponent(component)}${locale ? `?lang=${encodeURIComponent(locale)}` : ""}`
  const res = await fetch(url, {
    method: "POST",
    headers: authHeaders(),
    body: stringifyJson(props),
  })
  if (!res.ok) throw new Error(`BML fragment HTTP ${res.status}`)
  return res.text()
}
