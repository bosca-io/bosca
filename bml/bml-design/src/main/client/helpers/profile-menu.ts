import type { ClientContext } from "@bosca/bml";

/** Adds account-menu context activation to the shared native popover. */
export function profileMenu(ctx: ClientContext) {
  const panel = ctx.root.querySelector<HTMLDetailsElement>("details");
  const trigger = panel?.querySelector("summary");
  if (!panel || !trigger) return;
  const firstItem = () => ctx.root.querySelector<HTMLElement>('[role="menuitem"]:not(:disabled)');
  let focusOnOpen = false;
  const open = () => {
    if (panel.open) firstItem()?.focus({ preventScroll: true });
    else { focusOnOpen = true; panel.open = true; }
  };
  ctx.on<ToggleEvent>(panel, "toggle", event => {
    if (event.target !== panel || !panel.open || !focusOnOpen) return;
    focusOnOpen = false;
    requestAnimationFrame(() => {
      if (ctx.root.isConnected && panel.open) firstItem()?.focus({ preventScroll: true });
    });
  });
  ctx.on<MouseEvent>(trigger, "contextmenu", event => { event.preventDefault(); open(); });
  ctx.on<KeyboardEvent>(trigger, "keydown", event => {
    if (event.key === "ContextMenu" || (event.shiftKey && event.key === "F10")) {
      event.preventDefault(); open();
    }
  });
}
