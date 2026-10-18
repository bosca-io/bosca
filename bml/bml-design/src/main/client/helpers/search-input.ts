import type { ClientContext, IslandUpdate } from "@bosca/bml";

/** Keeps a retained search draft until the caller supplies a changed server value. */
export function searchInput(ctx: ClientContext) {
  const input = ctx.ref<HTMLInputElement>("query");
  const clear = ctx.ref<HTMLButtonElement>("clear");
  const action = ctx.ref<HTMLElement>("clearAction");
  let serverValue: string | undefined;
  const visibility = () => { if (action && input) action.hidden = !input.value; };
  const sync = () => {
    if (!input) return;
    const next = ctx.root.dataset.value ?? "";
    if (next !== serverValue) input.value = next;
    serverValue = next;
    input.placeholder = ctx.root.dataset.placeholder ?? "Search…";
    input.name = ctx.root.dataset.name ?? "q";
    input.setAttribute("aria-label", ctx.root.dataset.label ?? "Search");
    input.disabled = ctx.root.hasAttribute("data-disabled");
    if (clear) clear.disabled = input.disabled || input.readOnly;
    visibility();
  };
  if (input) ctx.on(input, "input", visibility);
  if (clear && input) ctx.on(clear, "click", () => {
    if (input.disabled || input.readOnly) return;
    input.value = "";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.focus();
  });
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => {
    if (event.detail.target.contains(ctx.root)) sync();
  });
  ctx.onUpdate(sync);
  sync();
}
