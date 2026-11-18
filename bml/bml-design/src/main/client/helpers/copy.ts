import type { ClientContext, IslandUpdate } from "@bosca/bml";

/** Copies the current value and reports only the latest request while its component remains mounted. */
export function clipboardCopy(ctx: ClientContext, refreshTooltip: () => void) {
  const button = ctx.ref<HTMLButtonElement>("copy");
  if (!button) return;
  const idle = ctx.ref<HTMLElement>("idleIcon");
  const done = ctx.ref<HTMLElement>("doneIcon");
  const message = ctx.ref<HTMLElement>("message");
  let value = ctx.root.dataset.copyValue ?? "";
  let label = ctx.root.dataset.copyLabel ?? "Copy";
  let timer: ReturnType<typeof setTimeout> | undefined;
  let request = 0;
  const feedback = (copied: boolean) => {
    if (idle) idle.hidden = copied;
    if (done) done.hidden = !copied;
    const text = copied ? "Copied" : label;
    button.setAttribute("aria-label", text);
    ctx.root.dataset.tooltipContent = text;
    refreshTooltip();
  };
  ctx.on(button, "click", async () => {
    clearTimeout(timer);
    const current = ++request;
    let copied = false;
    try {
      await navigator.clipboard.writeText(value);
      copied = true;
    } catch {
      // A denied or unavailable clipboard leaves the displayed value available for manual copying.
    }
    if (current !== request || !ctx.root.isConnected) return;
    feedback(copied);
    if (message) message.textContent = copied ? "Copied." : "Select and copy the value.";
    ctx.root.dispatchEvent(new CustomEvent("copyresult", { bubbles: true, detail: { copied } }));
    if (copied) timer = setTimeout(() => {
      feedback(false);
      if (message) message.textContent = "";
    }, 2000);
  });
  const sync = () => {
    const nextValue = ctx.root.dataset.copyValue ?? "";
    const nextLabel = ctx.root.dataset.copyLabel ?? "Copy";
    if (nextValue === value && nextLabel === label) return;
    request++;
    clearTimeout(timer);
    value = nextValue;
    label = nextLabel;
    button.dataset.copy = value;
    button.setAttribute("title", label);
    feedback(false);
    if (message) message.textContent = "";
  };
  ctx.onUpdate(sync);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => {
    if (event.detail.target.contains(ctx.root)) sync();
  });
  ctx.onUnmount(() => {
    request++;
    clearTimeout(timer);
  });
}
