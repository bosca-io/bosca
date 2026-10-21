import type { ClientContext, IslandUpdate } from "@bosca/bml";
import type { Content } from "vanilla-jsoneditor";
import { createJSONEditor, Mode, toTextContent } from "vanilla-jsoneditor/index.js";
import "./json-editor.css";
import { attachJsonAccessibility } from "./json-accessibility";

/** Adapts the structured engine shared with Studio to BML's lifecycle and native form values. */
export function mountJsonEditor(ctx: ClientContext) {
  const target = ctx.ref<HTMLDivElement>("editor"), field = ctx.ref<HTMLTextAreaElement>("value");
  const feedback = ctx.ref<HTMLParagraphElement>("feedback");
  if (!target || !field || !feedback) return;
  let disposed = false;
  let previousValue = ctx.root.dataset.value ?? "{}", previousOptions = "";
  let previousMode: Mode | undefined;
  let observedForm: HTMLFormElement | null = null;
  const report = () => {
    target.querySelector(".cm-content")?.setAttribute("aria-readonly", String(field.readOnly));
    let message = "";
    try { JSON.parse(field.value); } catch (error) { message = error instanceof Error ? error.message : String(error); }
    const serverError = ctx.root.dataset.error ?? "";
    feedback.textContent = [message, serverError].filter(Boolean).join(" · ");
    feedback.hidden = !feedback.textContent;
    ctx.root.setAttribute("aria-invalid", String(Boolean(feedback.textContent)));
  };
  const publish = (content: Content) => {
    if (disposed) return;
    const text = toTextContent(content).text;
    if (text !== field.value) {
      field.value = text;
      field.dispatchEvent(new Event("input", { bubbles: true }));
    }
    report();
  };
  const editor = createJSONEditor({ target, props: { content: { text: field.value }, mode: Mode.tree, onChange: publish, onChangeMode: report } });
  attachJsonAccessibility(ctx, target);
  const observer = new MutationObserver(() => sync());
  const sync = () => {
    if (disposed) return;
    const form = field.form;
    if (form !== observedForm) {
      observer.disconnect(); observedForm = form;
      if (form) observer.observe(form, { attributes: true, attributeFilter: ["data-bml-action-pending"] });
    }
    const disabled = ctx.root.hasAttribute("data-disabled");
    const readOnly = ctx.root.hasAttribute("data-readonly") || disabled || Boolean(form?.hasAttribute("data-bml-action-pending"));
    const mode = Object.values(Mode).find(mode => mode === ctx.root.dataset.mode) ?? Mode.tree;
    const label = ctx.root.dataset.label ?? "JSON editor", name = ctx.root.dataset.name ?? "";
    field.name = name; field.disabled = disabled; field.readOnly = readOnly;
    field.setAttribute("aria-label", label); target.inert = disabled;
    if (mode !== previousMode) { editor.updateProps({ mode }); previousMode = mode; }
    const options = JSON.stringify([label, readOnly]);
    if (options !== previousOptions) { editor.updateProps({ ariaLabel: label, readOnly }); previousOptions = options; }
    const value = ctx.root.dataset.value ?? "{}";
    if (value !== previousValue) {
      editor.validate();
      previousValue = value;
      field.value = value; field.defaultValue = value;
      editor.update({ text: value });
    }
    report();
    ctx.root.setAttribute("data-json-ready", "");
  };
  // Text mode debounces engine changes; validate() flushes them before native form serialization.
  ctx.on<InputEvent>(target, "input", event => {
    if (event.target instanceof Element && event.target.closest(".cm-content")) editor.validate();
  });
  ctx.on<Event>(document, "submit", event => {
    if (event.target === field.form) { editor.validate(); publish(editor.get()); }
  }, true);
  ctx.on<Event>(document, "reset", event => {
    if (event.target !== field.form) return;
    queueMicrotask(() => {
      if (disposed || event.defaultPrevented) return;
      editor.validate();
      field.value = previousValue; editor.update({ text: previousValue }); report();
    });
  }, true);
  ctx.onUpdate(sync);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => { if (event.detail.target.contains(ctx.root)) sync(); });
  ctx.onUnmount(() => {
    disposed = true; observer.disconnect();
    void editor.destroy();
    ctx.root.removeAttribute("data-json-ready");
  });
  sync();
  field.hidden = true;
}
