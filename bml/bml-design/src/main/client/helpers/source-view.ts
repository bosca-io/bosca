import type { ClientContext, IslandUpdate } from "@bosca/bml";
import { Compartment, EditorState } from "@codemirror/state";
import { EditorView, lineNumbers, highlightActiveLineGutter, highlightSpecialChars, drawSelection,
  dropCursor, rectangularSelection, crosshairCursor, highlightActiveLine, keymap } from "@codemirror/view";
import { syntaxHighlighting, defaultHighlightStyle, foldGutter, bracketMatching, foldKeymap } from "@codemirror/language";
import { defaultKeymap } from "@codemirror/commands";
import { highlightSelectionMatches, searchKeymap } from "@codemirror/search";
import { sourceHighlight } from "./source-highlight";
import { codeLanguage } from "./source-language";
import "./source-view.css";

/** Mount one read-only viewer. The application owns content, URLs, requested lines and updates. */
export function mountSourceView(ctx: ClientContext) {
  const content = ctx.ref<HTMLTextAreaElement>("content");
  const surface = ctx.ref<HTMLDivElement>("editor");
  if (!content || !surface) return;
  const language = new Compartment(), wrapping = new Compartment();
  let previousLanguage = "", previousLine = 0, previousWrapped: boolean | undefined;
  let disposed = false, languageRevision = 0;
  const view = new EditorView({
    parent: surface,
    state: EditorState.create({
      doc: content.value,
      extensions: [
        lineNumbers(), highlightActiveLineGutter(), highlightSpecialChars(), foldGutter(),
        drawSelection(), dropCursor(), EditorState.allowMultipleSelections.of(true),
        syntaxHighlighting(defaultHighlightStyle, { fallback: true }), bracketMatching(),
        rectangularSelection(), crosshairCursor(), highlightActiveLine(), highlightSelectionMatches(),
        keymap.of([...defaultKeymap, ...searchKeymap, ...foldKeymap]),
        syntaxHighlighting(sourceHighlight),
        language.of([]), wrapping.of([]),
        EditorState.readOnly.of(true), EditorView.editable.of(false),
        EditorView.contentAttributes.of({ tabindex: "0", "aria-readonly": "true",
          "aria-description": "Escape then Tab leaves the viewer" }),
      ],
    }),
  });
  const sync = () => {
    const path = ctx.root.dataset.path ?? "", explicit = ctx.root.dataset.language ?? "";
    const nextLanguage = JSON.stringify([path, explicit]);
    if (nextLanguage !== previousLanguage) {
      previousLanguage = nextLanguage;
      const revision = ++languageRevision;
      ctx.root.removeAttribute("data-source-ready");
      void codeLanguage(path, explicit).then(extension => {
        if (disposed || revision !== languageRevision) return;
        view.dispatch({ effects: language.reconfigure(extension) });
        ctx.root.setAttribute("data-source-ready", "");
      });
    }
    const wrapped = ctx.root.hasAttribute("data-wrapped");
    if (wrapped !== previousWrapped) {
      view.dispatch({ effects: wrapping.reconfigure(wrapped ? EditorView.lineWrapping : []) });
      ctx.ref<HTMLButtonElement>("wrap")?.setAttribute("aria-pressed", String(wrapped));
      previousWrapped = wrapped;
    }
    const label = ctx.root.dataset.label ?? "File source";
    view.contentDOM.setAttribute("aria-label", path ? `${label}: ${path}` : label);
    const line = Number(ctx.root.dataset.line);
    if (line !== previousLine && Number.isInteger(line) && line > 0 && line <= view.state.doc.lines) {
      const anchor = view.state.doc.line(line).from;
      view.dispatch({ selection: { anchor }, effects: EditorView.scrollIntoView(anchor, { y: "center" }) });
    }
    previousLine = line;
    view.requestMeasure();
  };
  ctx.on<MouseEvent>(ctx.root, "click", event => {
    const wrap = event.target instanceof Element ? event.target.closest<HTMLButtonElement>("[data-bml-ref=wrap]") : null;
    if (!wrap || wrap.closest("[data-bml-source-view]") !== ctx.root) return;
    const enabled = wrap.getAttribute("aria-pressed") !== "true";
    view.dispatch({ effects: wrapping.reconfigure(enabled ? EditorView.lineWrapping : []) });
    wrap.setAttribute("aria-pressed", String(enabled));
  });
  ctx.onUpdate(sync);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => {
    if (event.detail.target.contains(ctx.root)) sync();
  });
  ctx.onUnmount(() => { disposed = true; view.destroy(); ctx.root.removeAttribute("data-source-ready"); });
  sync();
  ctx.ref("fallback")?.remove();
}
