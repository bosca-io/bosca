import { Compartment, EditorState, Prec, Transaction } from "@codemirror/state";
import { EditorView, keymap, type ViewUpdate } from "@codemirror/view";
import { indentWithTab } from "@codemirror/commands";
import { syntaxHighlighting } from "@codemirror/language";
import { basicSetup } from "codemirror";
import type { Node as DocumentNode } from "@tiptap/pm/model";
import type { EditorView as DocumentView, NodeView, NodeViewConstructor } from "@tiptap/pm/view";
import { Selection, TextSelection } from "@tiptap/pm/state";
import { exitCode } from "@tiptap/pm/commands";
import { documentDiagram } from "./document-diagram";
import { codeLanguage } from "./source-language";
import { sourceHighlight } from "./source-highlight";
import "./source-view.css";

/** Code blocks use the same language/highlighting engines as the library's code fields. */
export function documentCodeViews(leaveEditor: () => boolean, history: { undo(): boolean; redo(): boolean }) {
  const mounted = new Set<CodeBlockView>();
  let editable = true;
  class CodeBlockView implements NodeView {
    readonly dom = document.createElement("div");
    private readonly language = new Compartment();
    private readonly editing = new Compartment();
    private readonly surface = document.createElement("div");
    private readonly header = document.createElement("div");
    private readonly diagramTarget = document.createElement("div");
    private readonly diagram = documentDiagram(this.diagramTarget);
    private readonly select = document.createElement("select");
    private readonly status = document.createElement("span");
    private readonly code: EditorView;
    private updating = false;
    private disposed = false;
    private exiting = false;
    private grammar = "";
    private grammarRevision = 0;
    private locked: boolean | undefined;
    constructor(private node: DocumentNode, private outer: DocumentView, private position: () => number | undefined) {
      this.dom.dataset.documentCode = "";
      this.dom.contentEditable = "false";
      const header = this.header, surface = this.surface, label = document.createElement("label");
      header.className = "document-code-header";
      label.textContent = "Code language ";
      this.select.setAttribute("aria-label", "Code language");
      for (const [value, name] of [["", "Plain text"], ["mermaid", "Mermaid"], ["javascript", "JavaScript"], ["typescript", "TypeScript"],
        ["html", "HTML"], ["css", "CSS"], ["json", "JSON"], ["graphql", "GraphQL"], ["sql", "SQL"], ["kotlin", "Kotlin"], ["python", "Python"]]) {
        this.select.add(new Option(name, value));
      }
      this.status.setAttribute("role", "status");
      label.append(this.select); header.append(label, this.status); this.dom.append(header, surface, this.diagramTarget);
      this.code = new EditorView({ parent: surface, state: EditorState.create({ doc: node.textContent, extensions: [
        Prec.highest(keymap.of([
          { key: "Mod-z", run: () => { if (editable) history.undo(); return true; } },
          { key: "Mod-Shift-z", run: () => { if (editable) history.redo(); return true; } },
          { key: "Mod-y", run: () => { if (editable) history.redo(); return true; } },
          { key: "Mod-Enter", run: () => {
            if (!editable) return true;
            const changed = exitCode(this.outer.state, this.outer.dispatch); if (changed) this.outer.focus(); return changed;
          } },
          ...["Left", "Right", "Up", "Down"].map(direction => ({ key: `Arrow${direction}`, run: () => this.escape(direction) })),
          { key: "Escape", run: () => {
            if (this.code.dom.querySelector(".cm-tooltip-autocomplete,.cm-search")) return false;
            this.exiting = true; return true;
          } },
          { key: "Tab", run: () => { const leave = this.exiting; this.exiting = false; return leave && leaveEditor(); } },
        ])), basicSetup, keymap.of([indentWithTab]), syntaxHighlighting(sourceHighlight),
        this.language.of([]), this.editing.of([]),
        EditorView.updateListener.of(update => this.forward(update)),
        EditorView.domEventHandlers({
          beforeinput: event => {
            if (event.inputType !== "historyUndo" && event.inputType !== "historyRedo") return false;
            event.preventDefault();
            if (editable) {
              if (event.inputType === "historyUndo") history.undo(); else history.redo();
            }
            return true;
          },
          keydown: event => { if (event.key !== "Escape" && event.key !== "Tab") this.exiting = false; return false; },
          blur: () => { this.exiting = false; },
        }),
      ] }) });
      this.select.addEventListener("change", this.changeLanguage);
      mounted.add(this); this.sync();
    }
    private changeLanguage = () => {
      const at = this.position();
      if (!editable || at === undefined) return;
      this.outer.dispatch(this.outer.state.tr.setNodeMarkup(at, undefined, { ...this.node.attrs, language: this.select.value || null }));
    };
    private forward(update: ViewUpdate) {
      if (this.updating || !this.code.hasFocus || !editable) return;
      const at = this.position();
      if (at === undefined) return;
      const selection = update.state.selection.main, start = at + 1;
      const current = this.outer.state.selection;
      if (!update.docChanged && current.anchor === start + selection.anchor && current.head === start + selection.head) return;
      const transaction = this.outer.state.tr;
      let shift = 0;
      update.changes.iterChanges((from, to, nextFrom, nextTo, text) => {
        const value = text.toString();
        transaction.replaceWith(start + from + shift, start + to + shift, value ? this.outer.state.schema.text(value) : []);
        shift += nextTo - nextFrom - (to - from);
      });
      transaction.setSelection(TextSelection.create(transaction.doc, start + selection.anchor, start + selection.head));
      this.outer.dispatch(transaction);
    }
    private escape(direction: string) {
      const selection = this.code.state.selection.main;
      if (!selection.empty) return false;
      const backwards = direction === "Left" || direction === "Up", at = this.position();
      const edge = direction === "Up" || direction === "Down" ? this.code.state.doc.lineAt(selection.head) : selection;
      if (at === undefined || (backwards ? edge.from !== 0 : edge.to !== this.code.state.doc.length)) return false;
      this.outer.dispatch(this.outer.state.tr.setSelection(Selection.near(
        this.outer.state.doc.resolve(at + (backwards ? 0 : this.node.nodeSize)), backwards ? -1 : 1,
      )).scrollIntoView());
      this.outer.focus(); return true;
    }
    setSelection(anchor: number, head: number) {
      this.updating = true;
      try { this.code.focus(); this.code.dispatch({ selection: { anchor, head } }); }
      finally { this.updating = false; }
    }
    update(node: DocumentNode) {
      if (node.type !== this.node.type) return false;
      this.node = node;
      const next = node.textContent, previous = this.code.state.doc.toString();
      if (next !== previous) {
        let from = 0, to = previous.length, nextTo = next.length;
        while (from < to && from < nextTo && previous[from] === next[from]) from++;
        while (to > from && nextTo > from && previous[to - 1] === next[nextTo - 1]) { to--; nextTo--; }
        this.updating = true;
        try { this.code.dispatch({ changes: { from, to, insert: next.slice(from, nextTo) }, annotations: Transaction.addToHistory.of(false) }); }
        finally { this.updating = false; }
      }
      this.sync(); return true;
    }
    sync() {
      if (this.locked !== !editable) {
        this.locked = !editable;
        this.select.disabled = !editable;
        this.code.dispatch({ effects: this.editing.reconfigure([
          EditorState.readOnly.of(!editable), EditorView.editable.of(editable),
          EditorView.contentAttributes.of({ "aria-label": "Document code block", "aria-readonly": String(!editable),
            "aria-description": "Escape then Tab leaves the document. Command or Control Enter exits the code block." }),
        ]) });
      }
      const language = typeof this.node.attrs.language === "string" ? this.node.attrs.language : "";
      if (!Array.from(this.select.options).some(option => option.value === language)) this.select.add(new Option(language, language));
      this.select.value = language;
      this.header.hidden = this.surface.hidden = !editable && language === "mermaid";
      this.diagram.update(this.node.textContent, language === "mermaid");
      if (this.grammar === language && this.grammarRevision) return;
      this.grammar = language;
      const revision = ++this.grammarRevision;
      void codeLanguage("", language).then(extension => {
        if (this.disposed || revision !== this.grammarRevision) return;
        this.code.dispatch({ effects: this.language.reconfigure(extension) });
        this.status.textContent = ""; this.dom.setAttribute("data-document-code-ready", "");
      }, () => { if (!this.disposed && revision === this.grammarRevision) this.status.textContent = "Syntax highlighting is unavailable."; });
    }
    stopEvent() { return true; }
    ignoreMutation() { return true; }
    destroy() {
      this.disposed = true; mounted.delete(this);
      this.select.removeEventListener("change", this.changeLanguage); this.diagram.destroy(); this.code.destroy();
    }
  }
  const codeBlock: NodeViewConstructor = (node, view, position) => new CodeBlockView(node, view, position);
  return { codeBlock, setEditable(value: boolean) { editable = value; mounted.forEach(view => view.sync()); } };
}
