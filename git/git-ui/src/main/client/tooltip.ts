import type { ClientContext } from "@bosca/bml";

/** Bosca tooltip presentation shared by buttons, links and explicit tooltip consumers. */
export function attachTooltip(
  ctx: ClientContext,
  trigger: HTMLElement = ctx.root,
) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let tooltip: HTMLDivElement | undefined;
  let label = "";
  const placement = ctx.root.dataset.tooltipPlacement ?? "top";
  const delay = Number(ctx.root.dataset.tooltipDelay ?? 200);
  const readLabel = () => {
    const title = trigger.getAttribute("title");
    if (title) {
      trigger.dataset.tooltip = title;
      trigger.removeAttribute("title");
    }
    label =
      ctx.root.dataset.tooltipContent ??
      trigger.dataset.tooltip ??
      trigger.getAttribute("aria-label") ??
      "";
    if (tooltip) tooltip.textContent = label;
  };
  const hide = () => {
    if (timer) clearTimeout(timer);
    if (tooltip?.matches(":popover-open")) tooltip.hidePopover();
    if (tooltip) {
      const ids = (trigger.getAttribute("aria-describedby") ?? "")
        .split(" ")
        .filter((id) => id && id !== tooltip?.id);
      if (ids.length) trigger.setAttribute("aria-describedby", ids.join(" "));
      else trigger.removeAttribute("aria-describedby");
    }
  };
  const position = () => {
    if (!tooltip) return;
    const bounds = trigger.getBoundingClientRect();
    const box = tooltip.getBoundingClientRect();
    let left = bounds.left + (bounds.width - box.width) / 2;
    let top = bounds.top - box.height - 8;
    if (placement === "bottom" || top < 8) top = bounds.bottom + 8;
    if (placement === "left") {
      left = bounds.left - box.width - 8;
      top = bounds.top + (bounds.height - box.height) / 2;
    }
    if (placement === "right") {
      left = bounds.right + 8;
      top = bounds.top + (bounds.height - box.height) / 2;
    }
    tooltip.style.left = `${Math.max(8, Math.min(left, innerWidth - box.width - 8))}px`;
    tooltip.style.top = `${Math.max(8, Math.min(top, innerHeight - box.height - 8))}px`;
  };
  const show = () => {
    readLabel();
    if (!label || !trigger.checkVisibility()) return;
    if (!tooltip) {
      tooltip = document.createElement("div");
      tooltip.className = "ui-tooltip";
      tooltip.id = `tooltip-${crypto.randomUUID()}`;
      tooltip.setAttribute("role", "tooltip");
      tooltip.setAttribute("popover", "manual");
      document.body.append(tooltip);
    }
    tooltip.textContent = label;
    tooltip.showPopover();
    position();
    const ids = new Set(
      (trigger.getAttribute("aria-describedby") ?? "")
        .split(" ")
        .filter(Boolean),
    );
    ids.add(tooltip.id);
    trigger.setAttribute("aria-describedby", [...ids].join(" "));
  };
  const schedule = () => {
    hide();
    timer = setTimeout(show, delay);
  };
  ctx.on(trigger, "pointerenter", schedule);
  ctx.on(trigger, "pointerleave", hide);
  ctx.on(trigger, "focusin", schedule);
  ctx.on(trigger, "focusout", hide);
  ctx.on(trigger, "click", hide);
  ctx.on<KeyboardEvent>(document, "keydown", (event) => {
    if (event.key === "Escape") hide();
  });
  ctx.on(window, "scroll", hide, true);
  ctx.on(window, "resize", hide);
  ctx.onUpdate(readLabel);
  ctx.onUnmount(() => {
    hide();
    tooltip?.remove();
  });
  readLabel();
}
