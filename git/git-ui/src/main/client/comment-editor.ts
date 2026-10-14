import type { ClientContext } from "@bosca/bml";

/** Size a native comment field and keep keyboard submission and blank states consistent. */
export function commentEditor(ctx: ClientContext) {
  const field = ctx.ref<HTMLTextAreaElement>("commentBody");
  if (!field) return;
  const form = field.form;
  const submit = form?.querySelector<HTMLButtonElement>('button[type="submit"]');
  const error = ctx.ref<HTMLElement>("commentError");
  const actions = new Map(
    [...ctx.root.querySelectorAll<HTMLButtonElement>('button:not([type="submit"])')]
      .map((button) => [button, button.disabled] as const),
  );
  const sync = () => {
    const pending = form?.hasAttribute("data-bml-action-pending") ?? false;
    field.readOnly = pending;
    for (const [button, disabled] of actions) {
      button.disabled = pending || disabled || button.hasAttribute("data-bml-action-pending");
    }
    if (error) error.hidden = pending;
    field.setAttribute("aria-invalid", String(Boolean(error) && !pending));
    const describedBy = [shortcut?.hidden ? null : shortcut?.id, pending ? null : error?.id]
      .filter(Boolean).join(" ");
    if (describedBy) field.setAttribute("aria-describedby", describedBy);
    else field.removeAttribute("aria-describedby");
    const minimum = Number.parseFloat(getComputedStyle(field).minHeight);
    field.style.height = "auto";
    field.style.height = `${Math.min(320, Math.max(minimum, field.scrollHeight + 2))}px`;
    if (submit) {
      submit.disabled = pending || ctx.root.hasAttribute("data-submit-disabled") || !field.value.trim();
    }
  };
  const shortcut = ctx.ref<HTMLElement>("shortcut");
  if (shortcut && !shortcut.hasAttribute("data-hint")) {
    const label = ctx.root.dataset.submitLabel ?? "comment";
    shortcut.textContent = /Mac|iPhone|iPad/.test(navigator.platform)
      ? `⌘Enter to ${label}`
      : `Ctrl+Enter to ${label}`;
    shortcut.hidden = ctx.root.hasAttribute("data-submit-disabled");
  }
  ctx.on(field, "input", sync);
  ctx.on<KeyboardEvent>(field, "keydown", (event) => {
    if (event.key !== "Enter" || (!event.metaKey && !event.ctrlKey) || event.isComposing) {
      return;
    }
    event.preventDefault();
    if (field.value.trim() && submit && !submit.disabled) form?.requestSubmit(submit);
  });
  const observer = new MutationObserver(sync);
  if (form) {
    observer.observe(form, { attributes: true, attributeFilter: ["data-bml-action-pending"] });
  }
  ctx.onUnmount(() => observer.disconnect());
  sync();
}
