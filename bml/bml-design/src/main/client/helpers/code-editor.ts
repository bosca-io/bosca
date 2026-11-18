import type { ClientContext, IslandUpdate } from "@bosca/bml";
import { Compartment, EditorState } from "@codemirror/state";
import { basicSetup } from "codemirror";
import { EditorView, keymap } from "@codemirror/view";
import { indentWithTab } from "@codemirror/commands";
import { syntaxHighlighting } from "@codemirror/language";
import { codeLanguage } from "./source-language";
import { sourceHighlight } from "./source-highlight";
import "./source-view.css";

/** A code field owns its CodeMirror lifecycle and submits its current text through a native field. */
export function mountCodeEditor(ctx: ClientContext) {
  const field = ctx.ref<HTMLTextAreaElement>("value"), surface = ctx.ref<HTMLDivElement>("editor");
  if (!field || !surface) return;
  const language = new Compartment(), editable = new Compartment(), wrapping = new Compartment();
  let supplied = ctx.root.dataset.value ?? "", grammar = "", revision = 0, disposed = false, previousLine = 0, exiting = false;
  const flush = () => { field.value = view.state.doc.toString(); };
  const view = new EditorView({ parent: surface, state: EditorState.create({ doc: field.value, extensions: [
    basicSetup, syntaxHighlighting(sourceHighlight),
    keymap.of([indentWithTab]),
    language.of([]), editable.of([]), wrapping.of([]),
    EditorView.updateListener.of(update => {
      if (!update.docChanged) return;
      flush();
      field.dispatchEvent(new Event("input", { bubbles: true }));
    }),
  ] }) });
  ctx.on<KeyboardEvent>(surface, "keydown", event => {
    const selector = ctx.root.dataset.exitFocus;
    if (!selector || !view.contentDOM.contains(event.target as Node)) return;
    if (event.key === "Escape" && view.state.selection.ranges.length === 1 && view.state.selection.main.empty &&
        !view.dom.querySelector(".cm-tooltip-autocomplete")) {
      exiting = true; view.setTabFocusMode(false);
      event.preventDefault();
    } else if (event.key === "Tab" && exiting) {
      exiting = false;
      const target = (field.form ?? ctx.root.parentElement)?.querySelector<HTMLElement>(selector);
      if (!target || target.matches(":disabled") || !target.checkVisibility()) return;
      event.preventDefault(); event.stopPropagation(); target.focus();
    }
  }, true);
  let observedForm: HTMLFormElement | null = null;
  const formObserver = new MutationObserver(() => sync());
  const optionsObserver = new MutationObserver(() => sync());
  const sync = () => {
    if (disposed) return;
    if (field.form !== observedForm) {
      formObserver.disconnect(); observedForm = field.form;
      if (observedForm) formObserver.observe(observedForm, { attributes: true, attributeFilter: ["data-bml-action-pending"] });
    }
    const next = ctx.root.dataset.value ?? "";
    if (next !== supplied) {
      supplied = next;
      if (next !== view.state.doc.toString()) view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: next } });
    }
    const readOnly = ctx.root.hasAttribute("data-readonly");
    const disabled = ctx.root.hasAttribute("data-disabled");
    const pending = observedForm?.hasAttribute("data-bml-action-pending") ?? false;
    field.readOnly = readOnly; field.disabled = disabled;
    const path = ctx.root.dataset.path ?? "", label = ctx.root.dataset.label ?? "Code editor";
    view.dispatch({ effects: [editable.reconfigure([
      EditorState.readOnly.of(readOnly || disabled || pending), EditorView.editable.of(!readOnly && !disabled && !pending),
      EditorView.contentAttributes.of({ "aria-label": path ? `${label} for ${path}` : label,
        "aria-description": "Escape then Tab leaves the editor", "aria-readonly": String(readOnly || pending),
        "aria-disabled": String(disabled), tabindex: disabled ? "-1" : "0" }),
    ]), wrapping.reconfigure(ctx.root.hasAttribute("data-wrapped") ? EditorView.lineWrapping : [])] });
    const nextGrammar = JSON.stringify([path, ctx.root.dataset.language ?? ""]);
    if (nextGrammar !== grammar) {
      grammar = nextGrammar;
      const current = ++revision;
      ctx.root.removeAttribute("data-code-ready");
      void codeLanguage(path, ctx.root.dataset.language ?? "").then(extension => {
        if (disposed || current !== revision) return;
        view.dispatch({ effects: language.reconfigure(extension) });
        ctx.root.setAttribute("data-code-ready", "");
      });
    }
    const line = Number(ctx.root.dataset.line);
    if (line !== previousLine && Number.isInteger(line) && line > 0 && line <= view.state.doc.lines) {
      const anchor = view.state.doc.line(line).from;
      view.dispatch({ selection: { anchor }, effects: EditorView.scrollIntoView(anchor, { y: "center" }) });
    }
    previousLine = line;
    flush(); view.requestMeasure();
  };
  field.hidden = true;
  optionsObserver.observe(ctx.root, { attributes: true, attributeFilter: ["data-value", "data-path", "data-language", "data-line", "data-wrapped", "data-label", "data-readonly", "data-disabled"] });
  ctx.on<Event>(field, "input", () => {
    if (field.value !== view.state.doc.toString()) view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: field.value } });
  });
  ctx.on<Event>(document, "bml:form-serialize", flush, true);
  ctx.on<Event>(document, "reset", event => {
    if (event.target !== field.form) return;
    queueMicrotask(() => {
      if (!disposed && !event.defaultPrevented) view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: supplied } });
    });
  }, true);
  ctx.onUpdate(sync);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => { if (event.detail.target.contains(ctx.root)) sync(); });
  ctx.onUnmount(() => { disposed = true; formObserver.disconnect(); optionsObserver.disconnect(); view.destroy(); });
  sync();
}
