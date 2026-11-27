import type { ClientContext, IslandUpdate } from "@bosca/bml";

/** Suggests an ASCII slug until the user edits it; native fields remain the form contract. */
export function slugInput(ctx: ClientContext) {
  const input = ctx.root.querySelector<HTMLInputElement>("input:not([type=hidden])");
  const marker = ctx.root.querySelector<HTMLInputElement>("[data-slug-manual]");
  if (!input || !marker) return;
  let manual = ctx.root.hasAttribute("data-manual"), generating = false, disposed = false;
  const sync = () => { manual = ctx.root.hasAttribute("data-manual"); marker.value = String(manual); };
  ctx.on(document, "input", event => {
    if (generating || !input.form || input.disabled || input.readOnly) return;
    if (event.target === input) { manual = true; marker.value = "true"; return; }
    const source = input.form.elements.namedItem(ctx.root.dataset.sourceField ?? "");
    if (manual || event.target !== source || !(source instanceof HTMLInputElement || source instanceof HTMLTextAreaElement)) return;
    const value = source.value.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
    if (input.value === value) return;
    input.value = value; generating = true;
    try { input.dispatchEvent(new Event("input", { bubbles: true })); }
    finally { generating = false; }
  });
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => { if (event.detail.target.contains(ctx.root)) sync(); });
  ctx.onUpdate(sync);
  ctx.on(document, "reset", event => { if (event.target === input.form) queueMicrotask(() => { if (!disposed) sync(); }); });
  ctx.onUnmount(() => { disposed = true; });
  sync();
}
