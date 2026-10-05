import { authHeaders } from "./graphql"
import { currentPageLocale, pendingDeferredIdentity } from "./deferred-runtime"
import { stringifyJson } from "./json"

/**
 * Calls a generated `<contract>` method: `POST /_bml/contract/<name>/<method>`
 * (a bml-server route, same origin as the page) with a JSON array of args, returning
 * the typed result. The bearer token is forwarded via the shared auth headers.
 */
export async function bmlContractCall<T = unknown>(
  contract: string,
  method: string,
  args: unknown[],
): Promise<T> {
  const identity = pendingDeferredIdentity()
  if (identity != null) await identity
  const locale = typeof document === "undefined" ? undefined : currentPageLocale()
  const url = `/_bml/contract/${contract}/${method}${locale ? `?lang=${encodeURIComponent(locale)}` : ""}`
  const res = await fetch(url, {
    method: "POST",
    headers: authHeaders(),
    body: stringifyJson(args),
  })
  if (!res.ok) throw new Error(`BML contract HTTP ${res.status}`)
  return (await res.json()) as T
}
