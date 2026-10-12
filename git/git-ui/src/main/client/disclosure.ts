import type { ClientContext } from "@bosca/bml";

/** Dismisses a component-owned disclosure and returns keyboard focus to its trigger. */
export function dismissibleDisclosure(ctx: ClientContext) {
  const close = (restoreFocus: boolean) => {
    const panel = ctx.ref<HTMLDetailsElement>("panel");
    if (!panel?.open) return;
    panel.open = false;
    if (restoreFocus) ctx.ref<HTMLElement>("trigger")?.focus();
  };
  ctx.on<KeyboardEvent>(document, "keydown", (event) => {
    if (event.key === "Escape") close(true);
  });
  ctx.on<PointerEvent>(document, "pointerdown", (event) => {
    const panel = ctx.ref<HTMLDetailsElement>("panel");
    if (event.target instanceof Node && !panel?.contains(event.target))
      close(false);
  });
  ctx.on<KeyboardEvent>(ctx.root, "keydown", (event) => {
    const panel = ctx.ref<HTMLDetailsElement>("panel");
    const menu = panel?.querySelector<HTMLElement>("[role=menu]");
    if (
      !panel ||
      !menu ||
      !["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)
    )
      return;
    event.preventDefault();
    panel.open = true;
    const items = [
      ...menu.querySelectorAll<HTMLElement>("[role=menuitem]"),
    ].filter((item) => !item.matches(":disabled"));
    const index = items.indexOf(document.activeElement as HTMLElement);
    const next =
      event.key === "Home"
        ? 0
        : event.key === "End"
          ? items.length - 1
          : event.key === "ArrowDown"
            ? (index + 1) % items.length
            : index < 0
              ? items.length - 1
              : (index - 1 + items.length) % items.length;
    items[next]?.focus();
  });
  ctx.on<MouseEvent>(ctx.root, "click", (event) => {
    const cancel = ctx.ref<HTMLButtonElement>("cancel");
    if (cancel && event.target instanceof Node && cancel.contains(event.target))
      close(true);
  });
}
