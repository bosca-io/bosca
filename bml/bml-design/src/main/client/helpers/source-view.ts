import type { ClientContext, IslandUpdate } from "@bosca/bml";
import { Compartment, EditorState } from "@codemirror/state";
import { EditorView, lineNumbers, highlightActiveLineGutter, highlightSpecialChars, drawSelection,
  dropCursor, rectangularSelection, crosshairCursor, highlightActiveLine, keymap } from "@codemirror/view";
import { HighlightStyle, syntaxHighlighting, defaultHighlightStyle, foldGutter, bracketMatching, foldKeymap } from "@codemirror/language";
import { defaultKeymap } from "@codemirror/commands";
import { highlightSelectionMatches, searchKeymap } from "@codemirror/search";
import { tags } from "@lezer/highlight";
import { codeLanguage } from "./source-language";
import "./source-view.css";

const highlight = HighlightStyle.define([
  { tag: tags.keyword, class: "source-keyword" },
  { tag: [tags.tagName, tags.angleBracket], class: "source-tag" },
  { tag: tags.attributeName, class: "source-attribute" },
  { tag: [tags.name, tags.deleted, tags.character, tags.propertyName, tags.macroName], class: "source-name" },
  { tag: [tags.function(tags.variableName), tags.labelName], class: "source-function" },
  { tag: [tags.color, tags.constant(tags.name), tags.standard(tags.name)], class: "source-constant" },
  { tag: [tags.definition(tags.name), tags.separator], class: "source-definition" },
  { tag: [tags.typeName, tags.className, tags.number, tags.changed, tags.annotation, tags.modifier, tags.self, tags.namespace], class: "source-type" },
  { tag: [tags.operator, tags.operatorKeyword, tags.url, tags.escape, tags.regexp, tags.link, tags.special(tags.string)], class: "source-operator" },
  { tag: [tags.meta, tags.comment], class: "source-comment" },
  { tag: tags.strong, class: "source-strong" },
  { tag: tags.emphasis, class: "source-emphasis" },
  { tag: tags.strikethrough, class: "source-strikethrough" },
  { tag: tags.link, class: "source-link" },
  { tag: tags.heading, class: "source-heading" },
  { tag: [tags.atom, tags.bool, tags.special(tags.variableName)], class: "source-constant" },
  { tag: [tags.processingInstruction, tags.string, tags.inserted], class: "source-string" },
  { tag: tags.invalid, class: "source-invalid" },
]);

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
        syntaxHighlighting(highlight),
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
