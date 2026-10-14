import type { ClientContext } from "@bosca/bml";

/** Positions interactive content at its trigger in the top layer, keeping it inside the viewport. */
export function positionedPopover(ctx: ClientContext) {
  const panel = ctx.ref<HTMLDetailsElement>("panel");
  const trigger = ctx.ref<HTMLElement>("trigger");
  const content = ctx.ref<HTMLElement>("content");
  if (!panel || !trigger || !content) return;
  const position = () => {
    if (!panel.open) return;
    const bounds = trigger.getBoundingClientRect();
    const width = Math.min(
      Number(ctx.root.dataset.width) || 360,
      innerWidth - 24,
    );
    content.style.width = width + "px";
    const height = content.getBoundingClientRect().height;
    const below = innerHeight - bounds.bottom - 12;
    const top =
      below >= height + 8 ? bounds.bottom + 8 : bounds.top - height - 8;
    content.style.left =
      Math.max(12, Math.min(bounds.right - width, innerWidth - width - 12)) +
      "px";
    content.style.top =
      Math.max(12, Math.min(top, innerHeight - height - 12)) + "px";
  };
  const hide = () => {
    if (content.matches(":popover-open")) content.hidePopover();
  };
  content.id = ctx.scoped("content");
  trigger.setAttribute("aria-controls", content.id);
  const observer = new ResizeObserver(position);
  observer.observe(content);
  ctx.on<ToggleEvent>(panel, "toggle", (event) => {
    if (event.target !== panel) return;
    trigger.setAttribute("aria-expanded", String(panel.open));
    if (!panel.open) {
      hide();
      return;
    }
    content.showPopover();
    position();
    content
      .querySelector<HTMLInputElement>("input:not(:disabled)")
      ?.focus({ preventScroll: true });
  });
  ctx.on(window, "resize", position);
  ctx.on(window, "scroll", position, true);
  ctx.onUpdate(position);
  ctx.onUnmount(() => {
    observer.disconnect();
    hide();
  });
  trigger.setAttribute("aria-expanded", "false");
}
