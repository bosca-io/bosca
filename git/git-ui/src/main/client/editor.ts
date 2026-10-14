import type { ClientContext } from "@bosca/bml";
import { basicSetup } from "codemirror";
import { EditorState, Compartment, Prec } from "@codemirror/state";
import { EditorView, keymap } from "@codemirror/view";
import { indentWithTab } from "@codemirror/commands";
import { oneDark } from "@codemirror/theme-one-dark";
import { javascript } from "@codemirror/lang-javascript";
import { python } from "@codemirror/lang-python";
import { java } from "@codemirror/lang-java";
import { rust } from "@codemirror/lang-rust";
import { go } from "@codemirror/lang-go";
import { json } from "@codemirror/lang-json";
import { markdown } from "@codemirror/lang-markdown";
import { html } from "@codemirror/lang-html";
import { css } from "@codemirror/lang-css";
import { yaml } from "@codemirror/lang-yaml";
import { sql } from "@codemirror/lang-sql";
import { StreamLanguage } from "@codemirror/language";
import { kotlin } from "@codemirror/legacy-modes/mode/clike";

export function codeLanguage(path: string) {
  const ext = path.split(".").at(-1)?.toLowerCase();
  if (ext === "ts" || ext === "tsx")
    return javascript({ typescript: true, jsx: ext === "tsx" });
  if (ext === "js" || ext === "jsx" || ext === "mjs")
    return javascript({ jsx: ext === "jsx" });
  if (ext === "kt" || ext === "kts") return StreamLanguage.define(kotlin);
  if (ext === "py") return python();
  if (ext === "java") return java();
  if (ext === "rs") return rust();
  if (ext === "go") return go();
  if (ext === "json") return json();
  if (ext === "md" || ext === "markdown") return markdown();
  if (ext === "html" || ext === "bml" || ext === "vue") return html();
  if (ext === "css" || ext === "scss") return css();
  if (ext === "yaml" || ext === "yml") return yaml();
  if (ext === "sql") return sql();
  return [];
}

/** Owns one editor's draft and releases all listeners and CodeMirror state on unmount. */
export function mountCodeEditor(
  ctx: ClientContext,
  readonly: boolean,
  preview?: (
    id: string,
    ref: string,
    path: string,
    source: string,
  ) => Promise<string>,
) {
  let textarea = ctx.ref<HTMLTextAreaElement>("content");
  const surface = ctx.ref<HTMLDivElement>("editor");
  if (!textarea || !surface) return;
  let container = surface.closest<HTMLElement>("[data-path]") ?? ctx.root;
  const original = textarea.value;
  const language = new Compartment();
  const wrap = new Compartment();
  let dirty = false;
  let escape = false;
  const view = new EditorView({
    parent: surface,
    state: EditorState.create({
      doc: original,
      extensions: [
        basicSetup,
        oneDark,
        language.of(codeLanguage(container.dataset.path ?? "")),
        EditorState.readOnly.of(readonly),
        EditorView.editable.of(!readonly),
        EditorView.contentAttributes.of({
          tabindex: "0",
          "aria-readonly": String(readonly),
          "aria-label": readonly ? "File source" : "File editor",
          "aria-description": "Escape then Tab leaves the editor",
        }),
        wrap.of([]),
        keymap.of([
          {
            key: "Escape",
            run: () => {
              escape = true;
              return false;
            },
          },
          {
            key: "Tab",
            run: () => {
              if (!escape) return false;
              escape = false;
              ctx.ref<HTMLButtonElement>("wrap")?.focus();
              return true;
            },
          },
          indentWithTab,
        ]),
        EditorView.updateListener.of((update) => {
          if (!update.docChanged) return;
          if (textarea) textarea.value = view.state.doc.toString();
          dirty = !readonly && view.state.doc.toString() !== original;
          container.dataset.dirty = String(dirty);
        }),
        Prec.highest(
          EditorView.theme({
            "&": {
              height: "100%",
              border: "none",
              borderRadius: "0",
              backgroundColor: "var(--bg-1)",
              color: "var(--fg-1)",
            },
            ".cm-gutters": {
              backgroundColor: "var(--bg-1)",
              color: "var(--fg-3)",
              border: "none",
            },
            ".cm-activeLine, .cm-activeLineGutter": {
              backgroundColor: "var(--bg-2)",
            },
            ".cm-scroller": {
              overflow: "auto",
              fontFamily: "var(--font-mono)",
            },
            ".cm-content": { padding: "12px 0" },
          }),
        ),
      ],
    }),
  });
  ctx.on<MouseEvent>(ctx.root, "click", (event) => {
    const wrapButton = (event.target as Element).closest<HTMLButtonElement>(
      "[data-bml-ref=wrap]",
    );
    if (!wrapButton) return;
    const enabled = wrapButton.getAttribute("aria-pressed") !== "true";
    view.dispatch({
      effects: wrap.reconfigure(enabled ? EditorView.lineWrapping : []),
    });
    wrapButton.setAttribute("aria-pressed", String(enabled));
  });
  let previewRevision = 0;
  ctx.on<MouseEvent>(ctx.root, "click", async (event) => {
    const previewButton = (event.target as Element).closest<HTMLButtonElement>(
      "[data-bml-ref=preview]",
    );
    const previewSurface = ctx.ref<HTMLElement>("previewSurface");
    const previewStatus = ctx.ref<HTMLElement>("previewStatus");
    if (!previewButton || !previewSurface || !preview) return;
    const showing = previewButton.getAttribute("aria-pressed") !== "true";
    previewButton.setAttribute("aria-pressed", String(showing));
    surface.hidden = showing;
    previewSurface.hidden = !showing;
    const revision = ++previewRevision;
    if (!showing) {
      if (previewStatus) previewStatus.hidden = true;
      return;
    }
    if (previewStatus) {
      previewStatus.textContent = "Rendering preview…";
      previewStatus.hidden = false;
    }
    try {
      const rendered = await preview(
        container.dataset.repository ?? "",
        container.dataset.ref ?? "",
        ctx.ref<HTMLInputElement>("path")?.value ?? "",
        view.state.doc.toString(),
      );
      if (revision !== previewRevision) return;
      previewSurface.innerHTML = rendered;
      if (previewStatus) previewStatus.hidden = true;
    } catch (error) {
      if (revision !== previewRevision) return;
      if (previewStatus)
        previewStatus.textContent =
          error instanceof Error ? error.message : "Preview failed.";
    }
  });
  ctx.on<InputEvent>(ctx.root, "input", (event) => {
    const path = ctx.ref<HTMLInputElement>("path");
    if (path && event.target === path) {
      dirty = true;
      view.dispatch({
        effects: language.reconfigure(codeLanguage(path.value)),
      });
    }
  });
  const editorLabel = `Code editor for ${container.dataset.path || "new file"}`;
  view.contentDOM.setAttribute("aria-label", editorLabel);
  const line = Number(new URLSearchParams(location.search).get("line"));
  if (Number.isInteger(line) && line > 0 && line <= view.state.doc.lines)
    view.dispatch({
      selection: { anchor: view.state.doc.line(line).from },
      effects: EditorView.scrollIntoView(view.state.doc.line(line).from, {
        y: "center",
      }),
    });
  ctx.on<BeforeUnloadEvent>(window, "beforeunload", (event) => {
    if (dirty) {
      event.preventDefault();
      event.returnValue = "";
    }
  });
  ctx.on<MouseEvent>(
    document,
    "click",
    (event) => {
      const anchor = (event.target as Element).closest<HTMLAnchorElement>(
        "a[href]",
      );
      if (
        !dirty ||
        !anchor ||
        anchor.target === "_blank" ||
        anchor.hasAttribute("download")
      )
        return;
      if (!confirm("Discard your uncommitted changes?")) {
        event.preventDefault();
        event.stopImmediatePropagation();
      } else dirty = false;
    },
    true,
  );
  ctx.onUpdate(() => {
    previewRevision++;
    container = surface.closest<HTMLElement>("[data-path]") ?? ctx.root;
    textarea = ctx.ref<HTMLTextAreaElement>("content");
    if (textarea) textarea.value = view.state.doc.toString();
    const next = container.dataset.nextHref;
    if (next) {
      dirty = false;
      location.assign(next);
    }
  });
  ctx.onUnmount(() => {
    previewRevision++;
    view.destroy();
  });
}
