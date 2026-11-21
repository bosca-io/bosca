import type { ClientContext } from "@bosca/bml";

/** Preserves native text values while preset buttons and previews follow the same field. */
export function colorInput(ctx: ClientContext) {
  const field = () => ctx.root.querySelector<HTMLInputElement>("[data-color-field]");
  let disposed = false;
  const paint = () => {
    const input = field();
    if (disposed || !input) return;
    for (const preview of ctx.root.querySelectorAll<HTMLElement>("[data-color-current],[data-color-value]")) {
      preview.style.backgroundColor = "";
      preview.style.backgroundColor = preview.hasAttribute("data-color-current") ? input.value : preview.dataset.colorValue ?? "";
    }
    for (const preset of ctx.root.querySelectorAll<HTMLButtonElement>("[data-color-preset]")) {
      preset.disabled = input.disabled || input.readOnly;
      preset.setAttribute("aria-pressed", String(preset.dataset.colorPreset === input.value));
    }
  };
  ctx.on(ctx.root, "input", paint);
  ctx.on(ctx.root, "click", event => {
    const input = field(), target = event.target instanceof Element ? event.target.closest<HTMLButtonElement>("[data-color-preset]") : null;
    if (!input || !target || !ctx.root.contains(target) || target.disabled || input.disabled || input.readOnly) return;
    event.preventDefault();
    input.value = target.dataset.colorPreset ?? "";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
    input.focus();
  });
  ctx.on(document, "reset", event => { if (event.target === field()?.form) queueMicrotask(paint); });
  ctx.onUpdate(paint);
  ctx.onUnmount(() => { disposed = true; });
  paint();
}
