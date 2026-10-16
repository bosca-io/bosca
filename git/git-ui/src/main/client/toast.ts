import type { ClientContext, IslandUpdate } from "@bosca/bml";

/** Present each server notification once, independently of later island updates. */
export function toast(ctx: ClientContext) {
  let sequence = 0;
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
  const close = () => {
    pause();
    if (!ctx.root.matches(":popover-open")) return;
    const focused = ctx.root.contains(document.activeElement);
    ctx.root.hidePopover();
    if (focused) {
      const target = returnFocus?.isConnected
        ? returnFocus
        : [
            ...(ctx.root
              .closest("[data-bml-island]")
              ?.querySelectorAll<HTMLElement>("button, a[href]") ?? []),
          ].find(
            (element) =>
              !ctx.root.contains(element) &&
              !element.matches(":disabled") &&
              element.checkVisibility(),
          );
      target?.focus({ preventScroll: true });
    }
  };
  const resume = () => {
    if (
      !ctx.root.matches(":popover-open") ||
      ctx.root.matches(":hover") ||
      ctx.root.contains(document.activeElement) ||
      document.hidden ||
      timer !== undefined
    ) return;
    started = performance.now();
    timer = setTimeout(close, remaining);
  };
  const sync = () => {
    const next = Number(ctx.root.dataset.sequence);
    const text = ctx.root.dataset.message ?? "";
    if (!text || !Number.isFinite(next) || next <= sequence || !message) return;
    sequence = next;
    pause();
    const active = document.activeElement;
    if (active instanceof HTMLElement && active !== document.body && !ctx.root.contains(active)) {
      returnFocus = active;
    }
    remaining = Number(ctx.root.dataset.duration) || 4000;
    message.setAttribute("role", ctx.root.dataset.tone === "error" ? "alert" : "status");
    message.setAttribute("aria-live", ctx.root.dataset.tone === "error" ? "assertive" : "polite");
    if (!ctx.root.matches(":popover-open")) ctx.root.showPopover();
    message.textContent = text;
    resume();
  };

  if (dismiss) ctx.on(dismiss, "click", close);
  ctx.on(ctx.root, "pointerenter", pause);
  ctx.on(ctx.root, "pointerleave", resume);
  ctx.on(ctx.root, "focusin", pause);
  ctx.on(ctx.root, "focusout", () => queueMicrotask(resume));
  ctx.on<KeyboardEvent>(ctx.root, "keydown", (event) => {
    if (event.key === "Escape") {
      event.stopPropagation();
      close();
    }
  });
  ctx.on(document, "visibilitychange", () => document.hidden ? pause() : resume());
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", (event) => {
    if (event.detail.target.contains(ctx.root)) sync();
  });
  ctx.onUnmount(() => {
    pause();
    if (ctx.root.matches(":popover-open")) ctx.root.hidePopover();
  });
  sync();
}
