import type { ClientContext } from "@bosca/bml";
import { basicSetup } from "codemirror";
import { EditorState, Compartment, Prec } from "@codemirror/state";
import { EditorView, keymap } from "@codemirror/view";
import { indentWithTab } from "@codemirror/commands";
import { oneDark } from "@codemirror/theme-one-dark";
import { StreamLanguage } from "@codemirror/language";

/** Load the selected grammar without adding every language to a file editor's initial payload. */
export async function codeLanguage(path: string) {
  const ext = path.split(".").at(-1)?.toLowerCase();
  if (ext === "ts" || ext === "tsx")
    return (await import("@codemirror/lang-javascript")).javascript({ typescript: true, jsx: ext === "tsx" });
  if (ext === "js" || ext === "jsx" || ext === "mjs")
    return (await import("@codemirror/lang-javascript")).javascript({ jsx: ext === "jsx" });
  if (ext === "kt" || ext === "kts") return StreamLanguage.define((await import("@codemirror/legacy-modes/mode/clike")).kotlin);
  if (ext === "py") return (await import("@codemirror/lang-python")).python();
  if (ext === "java") return (await import("@codemirror/lang-java")).java();
  if (ext === "rs") return (await import("@codemirror/lang-rust")).rust();
  if (ext === "go") return (await import("@codemirror/lang-go")).go();
  if (ext === "json") return (await import("@codemirror/lang-json")).json();
  if (ext === "md" || ext === "markdown") return (await import("@codemirror/lang-markdown")).markdown();
  if (ext === "html" || ext === "bml" || ext === "vue") return (await import("@codemirror/lang-html")).html();
  if (ext === "css" || ext === "scss") return (await import("@codemirror/lang-css")).css();
  if (ext === "yaml" || ext === "yml") return (await import("@codemirror/lang-yaml")).yaml();
  if (ext === "sql") return (await import("@codemirror/lang-sql")).sql();
  return [];
}

/** Owns one editor's draft and releases all listeners and CodeMirror state on unmount. */
export function mountCodeEditor(
  ctx: ClientContext,
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
  let wrapEnabled = false;
  const appearance = new Compartment();
  const palette = () => document.body.dataset.theme === "light" ? [] : oneDark;
  let dirty = false;
  let escape = false;
  let disposed = false, languageRevision = 0;
  const view = new EditorView({
    parent: surface,
    state: EditorState.create({
      doc: original,
      extensions: [
        basicSetup,
        appearance.of(palette()),
        language.of([]),
        EditorView.contentAttributes.of({
          tabindex: "0",
          "aria-readonly": "false",
          "aria-label": "File editor",
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
          dirty = view.state.doc.toString() !== original;
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
  const configureLanguage = (path: string) => {
    const revision = ++languageRevision;
    void codeLanguage(path).then(extension => {
      if (!disposed && revision === languageRevision) view.dispatch({ effects: language.reconfigure(extension) });
    });
  };
  configureLanguage(container.dataset.path ?? "");
  const appearanceObserver = new MutationObserver(() => {
    view.dispatch({ effects: appearance.reconfigure(palette()) });
  });
  appearanceObserver.observe(document.body, { attributes: true, attributeFilter: ["data-theme"] });
  ctx.onUnmount(() => appearanceObserver.disconnect());
  ctx.on<MouseEvent>(ctx.root, "click", (event) => {
    const wrapButton = (event.target as Element).closest<HTMLButtonElement>(
      "[data-bml-ref=wrap]",
    );
    if (!wrapButton) return;
    const enabled = !wrapEnabled;
    wrapEnabled = enabled;
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
      configureLanguage(path.value);
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
    ctx.ref<HTMLButtonElement>("wrap")?.setAttribute("aria-pressed", String(wrapEnabled));
    const next = container.dataset.nextHref;
    if (next) {
      dirty = false;
      location.assign(next);
    }
  });
  ctx.onUnmount(() => {
    disposed = true;
    previewRevision++;
    view.destroy();
  });
}
