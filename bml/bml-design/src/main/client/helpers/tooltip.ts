import type { ClientContext, IslandUpdate } from "@bosca/bml";

/** Keeps one tooltip with its component so custom properties inherit through the popover layer. */
export function attachTooltip(
  ctx: ClientContext,
  resolveTrigger: () => HTMLElement = () => ctx.root,
) {
  let trigger = resolveTrigger();
  let tooltip: HTMLSpanElement | undefined;
  let showTimer: ReturnType<typeof setTimeout> | undefined;
  let hideTimer: ReturnType<typeof setTimeout> | undefined;
  let nativeTitle: string | undefined;
  let label = "";
  let visible = false;
  let hovered = false;
  let focused = false;
  let tooltipHovered = false;
  const inside = (element: Element | undefined, target: EventTarget | null) =>
    target instanceof Node && !!element?.contains(target);
  const cancelTimers = () => {
    clearTimeout(showTimer);
    clearTimeout(hideTimer);
  };
  const restoreTitle = () => {
    if (nativeTitle != null && !trigger.hasAttribute("title")) trigger.setAttribute("title", nativeTitle);
  };
  const hide = () => {
    visible = false;
    cancelTimers();
    if (!tooltip) return;
    if (tooltip.matches(":popover-open")) tooltip.hidePopover();
    const ids = (trigger.getAttribute("aria-describedby") ?? "").split(/\s+/)
      .filter(id => id && id !== tooltip?.id);
    if (ids.length) trigger.setAttribute("aria-describedby", ids.join(" "));
    else trigger.removeAttribute("aria-describedby");
    tooltipHovered = false;
  };
  const position = () => {
    if (!tooltip?.matches(":popover-open")) return;
    const anchor = trigger.getBoundingClientRect();
    const box = tooltip.getBoundingClientRect();
    const width = document.documentElement.clientWidth;
    const height = innerHeight;
    if (anchor.bottom <= 0 || anchor.right <= 0 || anchor.top >= height || anchor.left >= width) {
      hide();
      return;
    }
    const placement = ctx.root.dataset.tooltipPlacement ?? "top";
    let left = anchor.left + (anchor.width - box.width) / 2;
    let top = anchor.top - box.height - 8;
    if (placement === "bottom" || placement === "top" && top < 8) {
      top = anchor.bottom + 8;
      if (top + box.height > height - 8 && anchor.top >= box.height + 16) top = anchor.top - box.height - 8;
    }
    if (placement === "left" || placement === "right") {
      top = anchor.top + (anchor.height - box.height) / 2;
      left = placement === "left" ? anchor.left - box.width - 8 : anchor.right + 8;
      if (left < 8) left = anchor.right + 8;
      else if (left + box.width > width - 8) left = anchor.left - box.width - 8;
    }
    tooltip.style.left = `${Math.max(8, Math.min(left, width - box.width - 8))}px`;
    tooltip.style.top = `${Math.max(8, Math.min(top, height - box.height - 8))}px`;
  };
  const readLabel = () => {
    const next = resolveTrigger();
    if (next !== trigger) {
      hide();
      restoreTitle();
      trigger = next;
      nativeTitle = undefined;
      hovered = focused = false;
    }
    const title = trigger.getAttribute("title");
    if (title != null) {
      nativeTitle = title;
      trigger.removeAttribute("title");
    }
    label = ctx.root.dataset.tooltipContent ?? nativeTitle ?? trigger.getAttribute("aria-label") ?? "";
    if (tooltip) tooltip.textContent = label;
    if (!label) hide();
    else position();
  };
  const show = () => {
    readLabel();
    if (!label || !ctx.root.isConnected || !trigger.isConnected || !trigger.checkVisibility()) return;
    const anchor = trigger.getBoundingClientRect();
    if (anchor.bottom <= 0 || anchor.right <= 0 || anchor.top >= innerHeight ||
        anchor.left >= document.documentElement.clientWidth) return;
    if (!tooltip?.isConnected) {
      tooltip = document.createElement("span");
      tooltip.className = "bosca-tooltip";
      tooltip.id = `tooltip-${crypto.randomUUID()}`;
      tooltip.setAttribute("role", "tooltip");
      tooltip.setAttribute("popover", "manual");
      // A closed details element hides every child outside its summary, including top-layer nodes.
      (ctx.root instanceof HTMLDetailsElement ? trigger : ctx.root).append(tooltip);
    }
    tooltip.textContent = label;
    if (!tooltip.matches(":popover-open")) tooltip.showPopover();
    visible = true;
    position();
    const ids = new Set((trigger.getAttribute("aria-describedby") ?? "").split(/\s+/).filter(Boolean));
    ids.add(tooltip.id);
    trigger.setAttribute("aria-describedby", [...ids].join(" "));
  };
  const schedule = () => {
    cancelTimers();
    if (tooltip?.matches(":popover-open")) return;
    const delay = Number(ctx.root.dataset.tooltipDelay ?? 200);
    showTimer = setTimeout(show, Number.isFinite(delay) ? Math.max(0, delay) : 200);
  };
  const leave = () => {
    if (hovered || focused || tooltipHovered) return;
    clearTimeout(showTimer);
    // Permit pointer travel across the gap between the trigger and its tooltip.
    hideTimer = setTimeout(hide, 100);
  };
  ctx.on<PointerEvent>(ctx.root, "pointerover", event => {
    if (inside(tooltip, event.target)) {
      tooltipHovered = true;
      clearTimeout(hideTimer);
    } else if (inside(trigger, event.target) && !inside(trigger, event.relatedTarget)) {
      hovered = true;
      schedule();
    }
  });
  ctx.on<PointerEvent>(ctx.root, "pointerout", event => {
    if (inside(tooltip, event.target) && !inside(tooltip, event.relatedTarget)) tooltipHovered = false;
    if (inside(trigger, event.target) && !inside(trigger, event.relatedTarget)) hovered = false;
    leave();
  });
  ctx.on<FocusEvent>(ctx.root, "focusin", event => {
    if (!inside(trigger, event.target)) return;
    focused = true;
    schedule();
  });
  ctx.on<FocusEvent>(ctx.root, "focusout", event => {
    if (inside(trigger, event.relatedTarget)) return;
    focused = false;
    leave();
  });
  ctx.on(ctx.root, "click", hide);
  // A top-layer node remains in its trigger's DOM subtree; clicking tooltip text is not an action.
  ctx.on<MouseEvent>(ctx.root, "click", event => {
    if (!inside(tooltip, event.target)) return;
    event.preventDefault();
    event.stopImmediatePropagation();
  }, true);
  ctx.on<KeyboardEvent>(document, "keydown", event => { if (event.key === "Escape") hide(); });
  ctx.on(window, "scroll", event => {
    if (inside(tooltip, event.target)) return;
    if (focused) position();
    else hide();
  }, true);
  ctx.on(window, "resize", position);
  ctx.onUpdate(readLabel);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => {
    if (!event.detail.target.contains(ctx.root)) return;
    readLabel();
    // Retained DOM leaves the native top layer during a surrounding replacement.
    if (visible) show();
  });
  ctx.onUnmount(() => {
    hide();
    tooltip?.remove();
    restoreTitle();
  });
  readLabel();
  return readLabel;
}
