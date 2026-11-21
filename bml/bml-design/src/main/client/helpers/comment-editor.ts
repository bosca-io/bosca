import type { ClientContext, IslandUpdate } from "@bosca/bml";
import { mentionMenu } from "./mention-menu";
import { profileMentionRange, replaceProfileMention } from "./profile-mentions";

/** Sizes a native comment field and keeps pending, error and keyboard behavior with its current form. */
export function commentEditor(ctx: ClientContext) {
  const field = ctx.ref<HTMLTextAreaElement>("commentBody");
  if (!field) return;
  let readOnly = field.readOnly, lastReadOnly = readOnly;
  const additionalDescriptions = (field.getAttribute("aria-describedby") ?? "").split(/\s+/)
    .filter(id => id && id !== `${field.id}-hint` && id !== `${field.id}-error`);
  const disabledActions = new WeakMap<HTMLButtonElement, boolean>();
  let observedForm: HTMLFormElement | null = null;
  let disposed = false;
  let composing = false;
  const mentions = ctx.root.hasAttribute("data-mentions") ? mentionMenu({
    root: ctx.root, field, id: field.id, kind: "profile",
    enabled: () => !composing && !field.readOnly && !field.disabled && document.activeElement === field,
    select: (choice, range) => {
      const replacement = replaceProfileMention(field.value, range, choice.handle!);
      field.value = replacement.value;
      field.setSelectionRange(replacement.cursor, replacement.cursor);
      field.dispatchEvent(new Event("input", { bubbles: true }));
      field.dispatchEvent(new Event("change", { bubbles: true }));
    },
  }) : null;
  const updateMentions = () => mentions?.update(field.selectionStart === field.selectionEnd
    ? profileMentionRange(field.value, field.selectionStart) : null);
  const observer = new MutationObserver(() => sync());
  const sync = () => {
    if (disposed) return;
    const form = field.form;
    if (form !== observedForm) {
      observer.disconnect();
      observedForm = form;
      if (form) observer.observe(form, { attributes: true, attributeFilter: ["data-bml-action-pending"] });
    }
    const pending = form?.hasAttribute("data-bml-action-pending") ?? false;
    if (field.readOnly !== lastReadOnly) readOnly = field.readOnly;
    const value = ctx.root.dataset.value ?? "";
    if (value !== field.defaultValue) {
      if (field.value !== value) field.value = value;
      field.defaultValue = value;
    }
    field.readOnly = readOnly || pending;
    lastReadOnly = field.readOnly;
    const error = ctx.ref<HTMLElement>("commentError");
    const shortcut = ctx.ref<HTMLElement>("shortcut");
    if (error) error.hidden = pending;
    if (shortcut && !shortcut.hasAttribute("data-hint")) {
      const label = ctx.root.dataset.submitLabel ?? "comment";
      shortcut.textContent = /Mac|iPhone|iPad/.test(navigator.platform) ? `⌘Enter to ${label}` : `Ctrl+Enter to ${label}`;
      shortcut.hidden = ctx.root.hasAttribute("data-submit-disabled");
    }
    field.setAttribute("aria-invalid", String(Boolean(error) && !pending));
    const descriptions = [shortcut && !shortcut.hidden && getComputedStyle(shortcut).display !== "none" ? shortcut.id : null, pending ? null : error?.id];
    field.setAttribute("aria-describedby", [...new Set([...additionalDescriptions, ...descriptions.filter(Boolean)])].join(" "));
    const minimum = Number.parseFloat(getComputedStyle(field).minHeight) || 78;
    field.style.height = "auto";
    field.style.height = `${Math.min(320, Math.max(minimum, field.scrollHeight + 2))}px`;
    const submit = form?.querySelector<HTMLButtonElement>('button[type="submit"]');
    if (submit) {
      if (!disabledActions.has(submit)) disabledActions.set(submit, submit.disabled);
      submit.disabled = disabledActions.get(submit) === true || pending || field.disabled || readOnly || ctx.root.hasAttribute("data-submit-disabled") || !field.value.trim();
    }
    for (const button of ctx.root.querySelectorAll<HTMLButtonElement>('button:not([type="submit"])')) {
      if (!disabledActions.has(button)) disabledActions.set(button, button.disabled);
      button.disabled = pending || disabledActions.get(button) === true || button.hasAttribute("data-bml-action-pending");
    }
    updateMentions();
  };
  ctx.on(field, "input", sync);
  ctx.on<KeyboardEvent>(field, "keydown", event => {
    if (mentions?.keydown(event)) { event.preventDefault(); event.stopPropagation(); return; }
    if (event.key !== "Enter" || (!event.metaKey && !event.ctrlKey) || event.isComposing) return;
    event.preventDefault();
    const submit = field.form?.querySelector<HTMLButtonElement>('button[type="submit"]');
    if (!field.readOnly && !field.disabled && field.value.trim() && submit && !submit.disabled) field.form?.requestSubmit(submit);
  });
  for (const event of ["keyup", "click", "select", "focus"]) ctx.on(field, event, updateMentions);
  ctx.on(document, "selectionchange", () => { if (document.activeElement === field) updateMentions(); });
  ctx.on(field, "compositionstart", () => { composing = true; mentions?.close(); });
  ctx.on(field, "compositionend", () => { composing = false; updateMentions(); });
  ctx.on<Event>(document, "reset", event => {
    if (event.target === field.form) queueMicrotask(sync);
  }, true);
  ctx.onUpdate(sync);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => {
    if (event.detail.target.contains(ctx.root)) sync();
  });
  ctx.onUnmount(() => { disposed = true; observer.disconnect(); mentions?.destroy(); });
  sync();
}
