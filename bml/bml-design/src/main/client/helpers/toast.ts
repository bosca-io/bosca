import type { ClientContext, IslandUpdate } from "@bosca/bml";

/** Presents each caller-issued sequence once, retaining the active notice through island updates. */
export function toast(ctx: ClientContext) {
  let sequence = 0;
  let visible = false;
  let disposed = false;
  let hovered = false;
  let focused: HTMLElement | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let remaining = 0;
  let started = 0;
  let returnFocus: HTMLElement | null = null;
  const message = ctx.ref<HTMLElement>("message");
  const dismiss = ctx.ref<HTMLButtonElement>("dismiss");
  const pause = () => {
    if (timer === undefined) return;
    clearTimeout(timer);
    timer = undefined;
    remaining = Math.max(0, remaining - (performance.now() - started));
  };
  const position = () => {
    if (disposed || !ctx.root.matches(":popover-open")) return;
    ctx.root.style.top = "";
    ctx.root.style.bottom = "";
    const bounds = ctx.root.getBoundingClientRect();
    if (bounds.bottom > innerHeight - 16) {
      ctx.root.style.top = `${Math.max(16, innerHeight - bounds.height - 16)}px`;
      ctx.root.style.bottom = "auto";
    }
    if (message) message.tabIndex = message.scrollHeight > message.clientHeight ? 0 : -1;
  };
  const close = () => {
    visible = false;
    hovered = false;
    focused = null;
    pause();
    if (!ctx.root.matches(":popover-open")) return;
    const hadFocus = ctx.root.contains(document.activeElement);
    ctx.root.hidePopover();
    if (hadFocus) {
      const target = returnFocus?.isConnected && !returnFocus.matches(":disabled") && returnFocus.checkVisibility()
        ? returnFocus
        : [...(ctx.root.closest("[data-bml-island]")?.querySelectorAll<HTMLElement>("button, a[href]") ?? [])]
          .find(element => !ctx.root.contains(element) && !element.matches(":disabled") && element.checkVisibility());
      target?.focus({ preventScroll: true });
    }
  };
  const resume = () => {
    if (disposed || !visible || !ctx.root.matches(":popover-open") || hovered ||
        ctx.root.contains(document.activeElement) || document.hidden || timer !== undefined) return;
    started = performance.now();
    timer = setTimeout(close, remaining);
  };
  const sync = () => {
    if (disposed || !ctx.root.isConnected || !message) return;
    const next = Number(ctx.root.dataset.sequence);
    const text = ctx.root.dataset.message ?? "";
    if (text && Number.isFinite(next) && next > sequence) {
      sequence = next;
      pause();
      const active = document.activeElement;
      if (active instanceof HTMLElement && active !== document.body && !ctx.root.contains(active)) returnFocus = active;
      remaining = Number(ctx.root.dataset.duration) || 4000;
      message.setAttribute("role", ctx.root.dataset.tone === "error" ? "alert" : "status");
      message.setAttribute("aria-live", ctx.root.dataset.tone === "error" ? "assertive" : "polite");
      message.textContent = text;
      visible = true;
    }
    if (!visible) return;
    // Detaching a preserved root removes its native top-layer state, but does not replay its notice.
    if (!ctx.root.matches(":popover-open")) ctx.root.showPopover();
    position();
    if (focused?.isConnected && ctx.root.contains(focused)) focused.focus({ preventScroll: true });
    resume();
  };
  if (dismiss) ctx.on(dismiss, "click", close);
  ctx.on(ctx.root, "pointerenter", () => { hovered = true; pause(); });
  ctx.on(ctx.root, "pointerleave", () => {
    if (ctx.root.isConnected && ctx.root.matches(":popover-open")) hovered = false;
    resume();
  });
  ctx.on<FocusEvent>(ctx.root, "focusin", event => {
    if (event.target instanceof HTMLElement) focused = event.target;
    pause();
  });
  ctx.on<FocusEvent>(ctx.root, "focusout", event => {
    if (event.relatedTarget instanceof Node && ctx.root.contains(event.relatedTarget)) return;
    queueMicrotask(() => {
      if (!ctx.root.contains(document.activeElement)) focused = null;
      resume();
    });
  });
  ctx.on<KeyboardEvent>(ctx.root, "keydown", event => {
    if (event.key === "Escape") {
      event.stopPropagation();
      close();
    }
  });
  ctx.on(document, "visibilitychange", () => document.hidden ? pause() : resume());
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => {
    if (event.detail.target.contains(ctx.root)) sync();
  });
  const observer = new ResizeObserver(position);
  observer.observe(ctx.root);
  if (message) observer.observe(message);
  ctx.on(window, "resize", position);
  ctx.onUnmount(() => {
    disposed = true;
    visible = false;
    hovered = false;
    focused = null;
    pause();
    observer.disconnect();
    if (ctx.root.matches(":popover-open")) ctx.root.hidePopover();
  });
  sync();
}
