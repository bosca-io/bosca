import type { ClientContext } from "@bosca/bml";

/** Copies a displayed value with stable icon feedback and an accessible status message. */
export function clipboardCopy(ctx: ClientContext) {
  const button = ctx.ref<HTMLButtonElement>("copy");
  if (!button) return;
  const idle = ctx.ref<HTMLElement>("idleIcon");
  const done = ctx.ref<HTMLElement>("doneIcon");
  const message = ctx.ref<HTMLElement>("message");
  const tooltip = button.closest<HTMLElement>("[data-tooltip-content]");
  const label = button.getAttribute("aria-label") ?? "Copy";
  let timer: ReturnType<typeof setTimeout> | undefined;
  const feedback = (copied: boolean) => {
    if (idle) idle.hidden = copied;
    if (done) done.hidden = !copied;
    const text = copied ? "Copied" : label;
    button.setAttribute("aria-label", text);
    if (tooltip) tooltip.dataset.tooltipContent = text;
  };
  ctx.on(button, "click", async () => {
    if (timer) clearTimeout(timer);
    let copied = false;
    try {
      await navigator.clipboard.writeText(button.dataset.copy ?? "");
      copied = true;
    } catch {
      // The displayed value remains available for manual copying.
    }
    if (!ctx.root.isConnected) return;
    feedback(copied);
    if (message)
      message.textContent = copied
        ? "Copied."
        : "Select and copy the value above.";
    ctx.root.dispatchEvent(
      new CustomEvent("copyresult", { bubbles: true, detail: { copied } }),
    );
    if (copied)
      timer = setTimeout(() => {
        feedback(false);
        if (message) message.textContent = "";
      }, 2000);
  });
  ctx.onUnmount(() => {
    if (timer) clearTimeout(timer);
  });
}
