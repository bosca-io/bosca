import type { Editor } from "@tiptap/core";
import type { Node as DocumentNode } from "@tiptap/pm/model";
import type { EditorView, NodeView, NodeViewConstructor, ViewMutationRecord } from "@tiptap/pm/view";
import { documentAction } from "./document-history";
import { documentReferencePanel } from "./document-references";

export type ContainerDefinition = { id: string; name: string; description: string; type: string; filters?: readonly string[] };

/** Reject invalid presentation configuration without changing the stored document. */
export function documentContainerOptions(value: string): readonly ContainerDefinition[] {
  const parsed: unknown = JSON.parse(value);
  if (!Array.isArray(parsed) || parsed.some(item => !item || typeof item.id !== "string" || !item.id ||
      typeof item.name !== "string" || typeof item.description !== "string" || typeof item.type !== "string" ||
      item.filters !== undefined && (!Array.isArray(item.filters) || item.filters.some((filter: unknown) => typeof filter !== "string")))) {
    throw new Error("Invalid document container choices.");
  }
  return parsed;
}

/** Wrap selected blocks and retain Studio's ordinary trailing paragraph. */
export function wrapDocumentContainer(editor: Editor, name: string) {
  if (!editor.isEditable) return false;
  return documentAction(editor.view, () => editor.chain().focus().wrapIn("container", { name }).command(({ tr, state }) => {
    const end = tr.selection.$to;
    for (let depth = end.depth; depth > 0; depth--) {
      if (end.node(depth).type.name !== "container") continue;
      const after = end.after(depth), parent = tr.doc.resolve(after), paragraph = state.schema.nodes.paragraph;
      if (paragraph && parent.parent.canReplaceWith(parent.index(), parent.index(), paragraph)) tr.insert(after, paragraph.create());
      return true;
    }
    return false;
  }).run());
}

/** Container presentation stays local; changes to the document go through its editor transaction. */
export function documentContainerViews(id: string, initial: readonly ContainerDefinition[], referenceRoot?: HTMLElement) {
  let definitions = initial, editable = true, sequence = 0;
  const mounted = new Set<ContainerView>();
  class ContainerView implements NodeView {
    readonly dom = document.createElement("section");
    readonly contentDOM = document.createElement("div");
    private readonly header = document.createElement("div");
    private readonly caption = document.createElement("strong");
    private readonly collapse = document.createElement("button");
    private readonly remove = document.createElement("button");
    private readonly body = document.createElement("div");
    private reference?: ReturnType<typeof documentReferencePanel>;
    private referenceKey = "";
    private expanded = true;
    constructor(private node: DocumentNode, private view: EditorView, private position: () => number | undefined) {
      this.dom.dataset.documentContainerView = "";
      this.header.className = "document-container-header";
      this.header.contentEditable = "false";
      this.contentDOM.className = "document-container-content";
      this.contentDOM.id = `${id}-container-${++sequence}`;
      this.body.id = `${this.contentDOM.id}-body`;
      this.collapse.type = "button"; this.remove.type = "button";
      this.collapse.setAttribute("aria-controls", this.body.id);
      this.collapse.addEventListener("click", this.toggle);
      this.remove.addEventListener("click", this.delete);
      this.header.append(this.caption, this.collapse, this.remove);
      this.body.append(this.contentDOM);
      this.dom.append(this.header, this.body);
      mounted.add(this); this.sync();
    }
    private toggle = () => { this.expanded = !this.expanded; this.sync(); };
    private delete = () => {
      const at = this.position();
      if (!editable || at === undefined) return;
      documentAction(this.view, () => this.view.dispatch(this.view.state.tr.deleteRange(at, at + this.node.nodeSize)));
      this.view.focus();
    };
    sync() {
      const definition = definitions.find(value => value.id === this.node.attrs.name);
      const referenceKey = referenceRoot && ["METADATA", "BIBLE"].includes(definition?.type ?? "")
        ? JSON.stringify([definition!.type, definition!.filters]) : "";
      if (referenceKey !== this.referenceKey) {
        this.reference?.destroy(); this.reference?.dom.remove(); this.reference = undefined;
        this.referenceKey = referenceKey;
        if (referenceKey) {
          this.reference = documentReferencePanel(referenceRoot!, {
            kind: definition!.type as "METADATA" | "BIBLE", filters: definition!.filters ?? [], current: () => this.node.attrs,
            change: patch => {
              const at = this.position();
              if (!editable || at === undefined) return;
              const current = this.view.state.doc.nodeAt(at);
              if (!current || current.type !== this.node.type) return;
              documentAction(this.view, () => this.view.dispatch(this.view.state.tr.setNodeMarkup(at, undefined, { ...current.attrs, ...patch })));
            },
          });
          this.body.prepend(this.reference.dom);
          queueMicrotask(() => this.reference?.start());
        }
      }
      const label = definition?.name || "Content";
      this.dom.dataset.documentContainer = String(this.node.attrs.name ?? "");
      this.dom.className = typeof this.node.attrs.class === "string" ? this.node.attrs.class : "";
      this.caption.textContent = label;
      this.caption.title = definition?.description ?? "";
      this.collapse.textContent = this.expanded ? "Collapse" : "Expand";
      this.collapse.setAttribute("aria-label", `${this.expanded ? "Collapse" : "Expand"} ${label} container`);
      this.collapse.setAttribute("aria-expanded", String(this.expanded));
      this.remove.textContent = "Remove";
      this.remove.setAttribute("aria-label", `Remove ${label} container`);
      this.remove.hidden = !editable; this.remove.disabled = !editable;
      this.body.hidden = !this.expanded;
      this.contentDOM.hidden = !this.expanded || Boolean(this.reference);
      this.reference?.update(editable);
    }
    update(node: DocumentNode) {
      if (node.type !== this.node.type) return false;
      this.node = node; this.sync(); return true;
    }
    reloadReference() { this.reference?.reload(); }
    stopEvent(event: Event) {
      if (event.type.startsWith("drag") || ["drop", "copy", "cut", "paste"].includes(event.type)) return false;
      return event.target instanceof Node && (this.header.contains(event.target) || Boolean(this.reference?.dom.contains(event.target)));
    }
    ignoreMutation(mutation: ViewMutationRecord) {
      return mutation.type === "attributes" && mutation.target === this.contentDOM ||
        mutation.target !== this.contentDOM && !this.contentDOM.contains(mutation.target);
    }
    destroy() {
      this.reference?.destroy();
      mounted.delete(this); this.collapse.removeEventListener("click", this.toggle); this.remove.removeEventListener("click", this.delete);
    }
  }
  const container: NodeViewConstructor = (node, view, position) => new ContainerView(node, view, position);
  return { container,
    setEditable(value: boolean) { editable = value; mounted.forEach(view => view.sync()); },
    setDefinitions(value: readonly ContainerDefinition[]) { definitions = value; mounted.forEach(view => view.sync()); },
    reloadReferences() { mounted.forEach(view => view.reloadReference()); },
  };
}

/** Both Studio and Content scripture attributes retain their original representation. */
export function documentBibleViews(root: HTMLElement) {
  let editable = true;
  const mounted = new Set<BibleView>();
  class BibleView implements NodeView {
    readonly dom = document.createElement("div");
    private readonly reference;
    constructor(private node: DocumentNode, private view: EditorView, private position: () => number | undefined) {
      this.dom.dataset.documentBible = ""; this.dom.contentEditable = "false";
      this.reference = documentReferencePanel(root, { kind: "BIBLE", standalone: true, filters: [], current: () => this.node.attrs,
        change: patch => {
          const at = this.position();
          if (!editable || at === undefined) return;
          const current = this.view.state.doc.nodeAt(at);
          if (!current || current.type !== this.node.type) return;
          documentAction(this.view, () => this.view.dispatch(this.view.state.tr.setNodeMarkup(at, undefined, { ...current.attrs, ...patch })));
        },
      });
      this.dom.append(this.reference.dom); mounted.add(this); this.sync();
      queueMicrotask(() => this.reference.start());
    }
    sync() { this.dom.className = typeof this.node.attrs.class === "string" ? this.node.attrs.class : ""; this.reference.update(editable); }
    reloadReference() { this.reference.reload(); }
    update(node: DocumentNode) { if (node.type !== this.node.type) return false; this.node = node; this.sync(); return true; }
    stopEvent(event: Event) { return !event.type.startsWith("drag") && !["drop", "copy", "cut", "paste"].includes(event.type); }
    ignoreMutation() { return true; }
    destroy() { mounted.delete(this); this.reference.destroy(); }
  }
  const bible: NodeViewConstructor = (node, view, position) => new BibleView(node, view, position);
  return { bible, setEditable(value: boolean) { editable = value; mounted.forEach(view => view.sync()); },
    reloadReferences() { mounted.forEach(view => view.reloadReference()); } };
}
