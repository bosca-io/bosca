import { HocuspocusProvider, HocuspocusProviderWebsocket } from "@hocuspocus/provider";
import * as Y from "yjs";
import { documentRevisions } from "./document-revisions";

export type DocumentConnectionStatus = "connecting" | "connected" | "offline" | "denied";

/** Shared transport lifecycle. The consumer supplies credentials through documenttokenrequest. */
export function documentCollaboration(root: HTMLElement, signal: AbortSignal,
  changed: () => void, statusChanged: (status: DocumentConnectionStatus) => void) {
  const endpoint = new URL(root.dataset.collaborationUrl || "/collaboration", location.href);
  if (endpoint.origin !== location.origin) throw new Error("Document collaboration must use the current origin.");
  endpoint.protocol = endpoint.protocol === "https:" ? "wss:" : "ws:";
  const document = new Y.Doc();
  const revisions = documentRevisions(document, changed);
  let disposed = false, denied = false, authenticated = false, synchronized = false;
  let status: DocumentConnectionStatus = "connecting";
  let resolveReady: () => void = () => {};
  let rejectReady: (reason: unknown) => void = () => {};
  const ready = new Promise<void>((resolve, reject) => { resolveReady = resolve; rejectReady = reject; });
  const setStatus = (value: DocumentConnectionStatus) => {
    if (disposed) return;
    status = value; statusChanged(value); changed();
  };
  const token = async () => {
    let response: Promise<string> | undefined;
    root.dispatchEvent(new CustomEvent("documenttokenrequest", { bubbles: true, detail: {
      respondWith(value: Promise<string>) { response ??= Promise.resolve(value); },
    } }));
    if (!response) throw new Error("Document authentication is unavailable.");
    const value = await response;
    if (disposed) throw new DOMException("Editor removed", "AbortError");
    if (!value.trim()) throw new Error("Sign in to edit this document.");
    return value;
  };
  const socket = new HocuspocusProviderWebsocket({ url: endpoint.href, autoConnect: false,
    onClose: ({ event }) => {
      authenticated = false;
      if ([4401, 4403].includes(event.code)) {
        denied = true; socket.disconnect(); setStatus("denied");
      } else if (!denied) setStatus("offline");
    },
    onStatus: ({ status: value }) => {
      if (!denied && value === "connecting") setStatus("connecting");
    },
  });
  const checkReady = () => {
    if (disposed || !authenticated || !provider.isSynced) return;
    synchronized = true; denied = false; setStatus("connected"); resolveReady();
  };
  const provider = new HocuspocusProvider({ name: root.dataset.collaborationRoom || "", document,
    websocketProvider: socket, token,
    onAuthenticated: () => { authenticated = true; denied = false; checkReady(); },
    onSynced: checkReady, onUnsyncedChanges: changed,
    onAuthenticationFailed: () => { denied = true; socket.disconnect(); setStatus("denied"); },
  });
  provider.attach();
  const connect = () => { void socket.connect().catch(() => { if (!denied) setStatus("offline"); }); };
  const refresh = () => {
    if (disposed) return;
    setStatus("connecting");
    if (socket.shouldConnect && socket.webSocket?.readyState === WebSocket.OPEN) {
      void provider.sendToken().catch(() => { denied = true; socket.disconnect(); setStatus("denied"); });
    } else connect();
  };
  const disconnect = () => { denied = true; authenticated = false; socket.disconnect(); setStatus("denied"); };
  root.addEventListener("documentcredentialschange", refresh, { signal });
  root.addEventListener("documentdisconnect", disconnect, { signal });
  const destroy = () => {
    if (disposed) return;
    disposed = true; rejectReady(new DOMException("Editor removed", "AbortError"));
    revisions.destroy(); provider.destroy(); socket.destroy(); document.destroy();
  };
  signal.addEventListener("abort", destroy, { once: true });
  if (signal.aborted) destroy(); else { statusChanged("connecting"); connect(); }
  return { document, provider, revisions, ready, destroy,
    get locked() { return denied || !synchronized; },
    get status() { return status; },
    get unsynced() { return provider.hasUnsyncedChanges; },
  };
}
