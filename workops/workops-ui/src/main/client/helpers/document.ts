import type { ClientContext } from "@bosca/bml";
import { workOpsBrowser as host } from "./browser-session";

/** Adapts Atlas's existing browser session to the shared editor's credential events. */
export function workOpsDocument(ctx: ClientContext) {
  let active = true;
  const removers: Array<() => void> = [];
  const editors = () => ctx.root.querySelectorAll<HTMLElement>("[data-bml-document-editor]");
  ctx.on<CustomEvent<{ respondWith: (token: Promise<string>) => void }>>(ctx.root, "documenttokenrequest", event => {
    event.stopPropagation();
    event.detail.respondWith((async () => {
      await host.boscaAuthFlow?.ready;
      if (!active) throw new DOMException("Editor removed", "AbortError");
      const token = await host.boscaAuth?.getToken();
      if (!token) throw new Error("Sign in to edit this document.");
      return token;
    })());
  });
  const dispatch = (name: string) => { for (const editor of editors()) editor.dispatchEvent(new Event(name)); };
  void (async () => {
    await host.boscaAuthFlow?.ready;
    if (!active) return;
    const auth = host.boscaAuth;
    if (!auth) return;
    for (const event of ["tokenRefreshed", "tokenAdopted", "signedIn"]) {
      removers.push(auth.on(event, () => dispatch("documentcredentialschange")));
    }
    removers.push(auth.on("signedOut", () => dispatch("documentdisconnect")));
  })().catch(() => dispatch("documentdisconnect"));
  ctx.onUnmount(() => { active = false; removers.forEach(remove => remove()); });
}
