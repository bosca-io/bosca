import type { ClientContext } from "@bosca/bml";

/** Native modal behavior, including focus containment and action-patch recovery. */
export function modalDialog(ctx: ClientContext) {
  let visible = false;
  let requested = false;
  let completed = false;
  let openRequest = "";
  const dialog = () => ctx.root.querySelector<HTMLDialogElement>(":scope > dialog");
  const trigger = () => ctx.root.querySelector<HTMLElement>(":scope > [data-bml-ref=trigger]");
  const sync = () => {
    const nextCompleted = ctx.root.hasAttribute("data-completed");
    const nextRequested = ctx.root.hasAttribute("data-force-open");
    const nextOpenRequest = ctx.root.dataset.openRequest ?? "";
    if (nextRequested && !requested) visible = true;
    // Each new server-issued request reopens the dialog, even after the viewer dismissed it.
    if (nextOpenRequest && nextOpenRequest !== openRequest) visible = true;
    requested = nextRequested;
    openRequest = nextOpenRequest;
    const finished = nextCompleted && !completed;
    completed = nextCompleted;
    if (finished) {
      close();
      return;
    }
    const panel = dialog();
    if (visible && panel?.isConnected && !panel.matches(":modal")) {
      const active = panel.contains(document.activeElement)
        ? (document.activeElement as HTMLElement)
        : null;
      // Preserved DOM retains drafts, but detaching removes a native dialog from the top layer.
      if (panel.open) panel.close();
      panel.showModal();
      const field = [
        ...panel.querySelectorAll<HTMLElement>(
          'input:not([type="hidden"]), textarea, select:not(.enhanced), .select-trigger',
        ),
      ].find(
        (element) => !element.matches(":disabled") && element.checkVisibility(),
      );
      (active ?? field ?? ctx.ref<HTMLElement>("cancel"))?.focus();
    }
  };
  const close = () => {
    visible = false;
    dialog()?.close();
    const target =
      (ctx.root.dataset.returnFocus
        ? ctx.root
            .closest("[data-bml-island]")
            ?.querySelector<HTMLElement>(ctx.root.dataset.returnFocus)
        : null) ?? trigger();
    target?.focus();
  };
  ctx.on<MouseEvent>(ctx.root, "click", (event) => {
    const target = event.target;
    if (!(target instanceof Node)) return;
    if (target instanceof Element && target.closest("[data-bml-design-dialog]") !== ctx.root) return;
    if (trigger()?.contains(target) && !trigger()?.matches(":disabled")) {
      visible = true;
      sync();
    } else if (
      ctx.ref("close")?.contains(target) ||
      ctx.ref("cancel")?.contains(target) ||
      target instanceof Element && !!target.closest("[data-dialog-close]")
    ) {
      close();
    } else if (target === dialog()) {
      const bounds = dialog()?.getBoundingClientRect();
      if (!bounds) return;
      if (
        event.clientX < bounds.left ||
        event.clientX > bounds.right ||
        event.clientY < bounds.top ||
        event.clientY > bounds.bottom
      )
        close();
    }
  });
  ctx.on<KeyboardEvent>(ctx.root, "keydown", (event) => {
    const panel = dialog();
    if (event.key !== "Tab" || !panel?.open) return;
    if (event.target instanceof Element && event.target.closest("[data-bml-design-dialog]") !== ctx.root) return;
    const focusable = [
      ...panel.querySelectorAll<HTMLElement>(
        "button, input, select, textarea, a[href], [tabindex]",
      ),
    ].filter(
      (element) =>
        element.tabIndex >= 0 &&
        !element.matches(":disabled") &&
        element.getClientRects().length > 0 &&
        getComputedStyle(element).visibility !== "hidden",
    );
    const first = focusable[0];
    const last = focusable.at(-1);
    if (!first || !last) return;
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  });
  ctx.on<Event>(
    ctx.root,
    "cancel",
    (event) => {
      if (event.target !== dialog()) return;
      event.preventDefault();
      close();
    },
    true,
  );
  ctx.onUpdate(sync);
  ctx.on<CustomEvent<{ target: Element }>>(document, "bml:update", (event) => {
    if (event.detail?.target.contains(ctx.root)) sync();
  });
  ctx.onUnmount(() => dialog()?.close());
  const panel = dialog();
  if (panel) {
    panel.id = ctx.scoped("dialog");
    trigger()?.setAttribute("aria-controls", panel.id);
    trigger()?.setAttribute("aria-haspopup", "dialog");
  }
  sync();
}
