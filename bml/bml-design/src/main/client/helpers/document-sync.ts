import type { HocuspocusProvider } from "@hocuspocus/provider";

/** Submission must observe persistence acknowledgements before its canonical body is saved. */
export function waitForDocumentSync(provider: Pick<HocuspocusProvider, "hasUnsyncedChanges" | "on" | "off">,
  signal: AbortSignal, timeout = 30_000): Promise<void> {
  if (signal.aborted) return Promise.reject(new DOMException("Editor removed", "AbortError"));
  if (!provider.hasUnsyncedChanges) return Promise.resolve();
  return new Promise<void>((resolve, reject) => {
    const finish = (failure?: Error) => {
      clearTimeout(timer);
      provider.off("unsyncedChanges", changed);
      provider.off("authenticationFailed", denied);
      provider.off("destroy", removed);
      signal.removeEventListener("abort", removed);
      if (failure) reject(failure); else resolve();
    };
    const changed = () => { if (!provider.hasUnsyncedChanges) finish(); };
    const denied = () => finish(new Error("Document access was lost. Sign in or reload before saving."));
    const removed = () => finish(new DOMException("Editor removed", "AbortError"));
    const timer = setTimeout(() => finish(new Error("Could not sync this document. Your draft is retained. Reconnect and save again.")), timeout);
    provider.on("unsyncedChanges", changed);
    provider.on("authenticationFailed", denied);
    provider.on("destroy", removed);
    signal.addEventListener("abort", removed, { once: true });
    changed();
  });
}
