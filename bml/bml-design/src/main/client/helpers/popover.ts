import type { ClientContext, IslandUpdate } from "@bosca/bml";

/** Enhances a native disclosure with a component-owned, viewport-fitted top-layer surface. */
export function positionedPopover(ctx: ClientContext) {
  const panel = ctx.ref<HTMLDetailsElement>("panel");
  const trigger = ctx.ref<HTMLElement>("trigger");
  const content = ctx.ref<HTMLElement>("content");
  if (!panel || !trigger || !content) return;
  content.setAttribute("popover", "manual");
  content.id = ctx.scoped("content");
  trigger.setAttribute("aria-controls", content.id);
  trigger.setAttribute("aria-expanded", String(panel.open));
  const hide = () => {
    if (content.matches(":popover-open")) content.hidePopover();
  };
  const close = (restoreFocus: boolean) => {
    if (!panel.open) return;
    panel.open = false;
    trigger.setAttribute("aria-expanded", "false");
    hide();
    if (restoreFocus) trigger.focus({ preventScroll: true });
  };
  const position = () => {
    if (!panel.open) return;
    const bounds = trigger.getBoundingClientRect();
    const viewport = document.documentElement;
    const requested = Number(ctx.root.dataset.width);
    const width = Math.max(0, Math.min(requested > 0 ? requested : 360, viewport.clientWidth - 24));
    content.style.width = `${width}px`;
    const height = content.getBoundingClientRect().height;
    const below = innerHeight - bounds.bottom - 12;
    const top = below >= height + 8 ? bounds.bottom + 8 : bounds.top - height - 8;
    content.style.left = `${Math.max(12, Math.min(bounds.right - width, viewport.clientWidth - width - 12))}px`;
    content.style.top = `${Math.max(12, Math.min(top, innerHeight - height - 12))}px`;
  };
  const sync = (focus: boolean) => {
    trigger.setAttribute("aria-expanded", String(panel.open));
    if (!panel.open) {
      hide();
      return;
    }
    if (!content.matches(":popover-open")) content.showPopover();
    position();
    if (focus) content.querySelector<HTMLInputElement>("input:not(:disabled)")?.focus({ preventScroll: true });
  };
  const observer = new ResizeObserver(position);
  observer.observe(content);
  ctx.on<ToggleEvent>(panel, "toggle", event => {
    if (event.target === panel) sync(!content.contains(document.activeElement));
  });
  ctx.on<KeyboardEvent>(document, "keydown", event => {
    if (event.key === "Escape") close(true);
  });
  ctx.on<PointerEvent>(document, "pointerdown", event => {
    if (event.target instanceof Node && !panel.contains(event.target)) close(false);
  });
  ctx.on<MouseEvent>(ctx.root, "click", event => {
    const cancel = ctx.ref<HTMLElement>("cancel");
    if (event.target instanceof Node && cancel?.contains(event.target)) {
      event.stopPropagation();
      close(true);
    }
  });
  ctx.on<KeyboardEvent>(ctx.root, "keydown", event => {
    if (event.key === "Escape" && panel.open) {
      event.preventDefault();
      event.stopPropagation();
      close(true);
      return;
    }
    const menu = content.querySelector<HTMLElement>("[role=menu]");
    if (!menu || !["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
    const items = [...menu.querySelectorAll<HTMLElement>("[role=menuitem]")]
      .filter(item => !item.matches(":disabled") && item.tabIndex >= 0);
    if (!items.length) return;
    event.preventDefault();
    panel.open = true;
    sync(false);
    const index = items.indexOf(document.activeElement as HTMLElement);
    const next = event.key === "Home" ? 0 : event.key === "End" ? items.length - 1
      : event.key === "ArrowDown" ? (index + 1) % items.length
      : index < 0 ? items.length - 1 : (index - 1 + items.length) % items.length;
    items[next]?.focus();
  });
  ctx.on(window, "resize", position);
  ctx.on(window, "scroll", position, true);
  ctx.onUpdate(() => sync(false));
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => {
    if (event.detail.target.contains(ctx.root)) sync(false);
  });
  ctx.onUnmount(() => {
    observer.disconnect();
    hide();
  });
  sync(false);
}
