import type { ClientContext, IslandUpdate } from "@bosca/bml";
import { Editor, type JSONContent } from "@tiptap/core";
import StarterKit from "@tiptap/starter-kit";
import Link from "@tiptap/extension-link";
import Underline from "@tiptap/extension-underline";
import Subscript from "@tiptap/extension-subscript";
import Superscript from "@tiptap/extension-superscript";
import Table from "@tiptap/extension-table";
import TableRow from "@tiptap/extension-table-row";
import TableHeader from "@tiptap/extension-table-header";
import TableCell from "@tiptap/extension-table-cell";
import TaskList from "@tiptap/extension-task-list";
import TaskItem from "@tiptap/extension-task-item";
import TextAlign from "@tiptap/extension-text-align";
import Image from "@tiptap/extension-image";
import Placeholder from "@tiptap/extension-placeholder";
import "./document-editor.css";

const emptyDocument: JSONContent = { type: "doc", content: [{ type: "paragraph" }] };
// Studio documents may carry the owning content identity alongside an image URL.
const DocumentImage = Image.extend({
  addAttributes() { return { ...this.parent?.(), metadataId: { default: null } }; },
});

/** Adapt TipTap's document JSON to a native BML form field and component lifecycle. */
export function mountDocumentEditor(ctx: ClientContext) {
  const target = ctx.ref<HTMLDivElement>("editor"), field = ctx.ref<HTMLTextAreaElement>("value");
  const toolbar = ctx.ref<HTMLDivElement>("toolbar"), feedback = ctx.ref<HTMLParagraphElement>("feedback");
  const insert = ctx.ref<HTMLDivElement>("insert"), url = ctx.ref<HTMLInputElement>("url");
  const block = ctx.ref<HTMLSelectElement>("block"), alignment = ctx.ref<HTMLSelectElement>("alignment");
  if (!target || !field || !toolbar || !feedback || !insert || !url || !block || !alignment) return;
  let previousValue = ctx.root.dataset.value ?? JSON.stringify(emptyDocument);
  let disposed = false, contentError = "", pending = false, observedForm: HTMLFormElement | null = null;
  let insertKind: "link" | "image" = "link", insertTrigger: HTMLElement | null = null;
  let selection = { from: 0, to: 0 };
  let leaveOnTab = false;
  const editor = new Editor({
    element: target,
    content: emptyDocument,
    extensions: [StarterKit, Link.configure({ openOnClick: false, defaultProtocol: "https" }),
      Underline, Subscript, Superscript, Table, TableRow, TableHeader, TableCell,
      TaskList, TaskItem.configure({ nested: true }), TextAlign.configure({ types: ["heading", "paragraph"] }),
      DocumentImage, Placeholder.configure({ placeholder: () => ctx.root.dataset.placeholder ?? "Start writing…" })],
  });
  const commands: Record<string, () => boolean> = {
    bold: () => editor.chain().focus().toggleBold().run(),
    italic: () => editor.chain().focus().toggleItalic().run(),
    underline: () => editor.chain().focus().toggleUnderline().run(),
    strike: () => editor.chain().focus().toggleStrike().run(),
    code: () => editor.chain().focus().toggleCode().run(),
    superscript: () => editor.chain().focus().toggleSuperscript().run(),
    subscript: () => editor.chain().focus().toggleSubscript().run(),
    bulletList: () => editor.chain().focus().toggleBulletList().run(),
    orderedList: () => editor.chain().focus().toggleOrderedList().run(),
    taskList: () => editor.chain().focus().toggleTaskList().run(),
    blockquote: () => editor.chain().focus().toggleBlockquote().run(),
    codeBlock: () => editor.chain().focus().toggleCodeBlock().run(),
    horizontalRule: () => editor.chain().focus().setHorizontalRule().run(),
    table: () => editor.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run(),
    addRowAfter: () => editor.chain().focus().addRowAfter().run(),
    addColumnAfter: () => editor.chain().focus().addColumnAfter().run(),
    deleteRow: () => editor.chain().focus().deleteRow().run(),
    deleteColumn: () => editor.chain().focus().deleteColumn().run(),
    toggleHeaderRow: () => editor.chain().focus().toggleHeaderRow().run(),
    mergeCells: () => editor.chain().focus().mergeCells().run(),
    splitCell: () => editor.chain().focus().splitCell().run(),
    deleteTable: () => editor.chain().focus().deleteTable().run(),
    undo: () => editor.chain().focus().undo().run(),
    redo: () => editor.chain().focus().redo().run(),
  };
  const controls = () => [...toolbar.querySelectorAll<HTMLButtonElement | HTMLSelectElement>("button,select")]
    .filter(control => !control.disabled && !control.closest("[hidden]"));
  const syncToolbar = () => {
    const locked = !editor.isEditable;
    for (const control of toolbar.querySelectorAll<HTMLButtonElement | HTMLSelectElement>("button,select")) {
      control.disabled = locked;
      const command = control.dataset.documentCommand;
      if (command && control.hasAttribute("aria-pressed")) control.setAttribute("aria-pressed", String(editor.isActive(command)));
      if (!locked && command === "undo") control.disabled = !editor.can().undo();
      if (!locked && command === "redo") control.disabled = !editor.can().redo();
      if (!locked && command === "mergeCells") control.disabled = !editor.can().mergeCells();
      if (!locked && command === "splitCell") control.disabled = !editor.can().splitCell();
    }
    const level: unknown = editor.getAttributes("heading").level;
    block.value = typeof level === "number" ? String(level) : "paragraph";
    const textAlign: unknown = editor.getAttributes("heading").textAlign ?? editor.getAttributes("paragraph").textAlign;
    alignment.value = typeof textAlign === "string" ? textAlign : "left";
    const tableActions = ctx.ref<HTMLElement>("tableActions");
    if (tableActions) tableActions.hidden = !editor.isActive("table");
    const available = controls(), current = available.find(control => control === document.activeElement)
      ?? available.find(control => control.tabIndex === 0) ?? available[0];
    for (const control of toolbar.querySelectorAll<HTMLElement>("button,select")) control.tabIndex = control === current ? 0 : -1;
  };
  const report = () => {
    const requiredError = ctx.root.hasAttribute("data-required") && editor.isEmpty ? "Enter document content." : "";
    field.setCustomValidity(contentError || requiredError);
    feedback.textContent = contentError || ctx.root.dataset.error || "";
    feedback.hidden = !feedback.textContent;
    const invalid = Boolean(feedback.textContent);
    editor.view.dom.setAttribute("aria-invalid", String(invalid));
    ctx.root.setAttribute("aria-invalid", String(invalid));
  };
  const publish = () => {
    if (disposed || contentError) return;
    field.value = JSON.stringify(editor.getJSON());
    field.dispatchEvent(new Event("input", { bubbles: true }));
    field.dispatchEvent(new Event("change", { bubbles: true }));
    ctx.root.dispatchEvent(new CustomEvent("documentchange", { bubbles: true, detail: { document: editor.getJSON() } }));
    report();
  };
  const load = (value: string) => {
    try {
      const document: JSONContent = JSON.parse(value);
      if (!document || document.type !== "doc") throw new Error("Expected a ProseMirror doc object.");
      const check = (node: JSONContent) => {
        const type = node.type ? editor.schema.nodes[node.type] : undefined;
        if (!type) throw new Error(`Unsupported document node: ${node.type ?? "missing type"}.`);
        for (const attribute of Object.keys(node.attrs ?? {})) {
          if (!Object.hasOwn(type.spec.attrs ?? {}, attribute)) throw new Error(`Unsupported ${node.type} attribute: ${attribute}.`);
        }
        for (const mark of node.marks ?? []) {
          const type = editor.schema.marks[mark.type];
          if (!type) throw new Error(`Unsupported document mark: ${mark.type}.`);
          for (const attribute of Object.keys(mark.attrs ?? {})) {
            if (!Object.hasOwn(type.spec.attrs ?? {}, attribute)) throw new Error(`Unsupported ${mark.type} attribute: ${attribute}.`);
          }
        }
        node.content?.forEach(check);
      };
      check(document);
      const parsed = editor.schema.nodeFromJSON(document);
      parsed.check();
      if (!parsed.eq(editor.state.doc)) editor.commands.setContent(document, false, {}, { errorOnInvalidContent: true });
      contentError = "";
    } catch (error) {
      contentError = error instanceof Error ? error.message : String(error);
    }
    field.value = value;
    field.defaultValue = value;
  };
  const observer = new MutationObserver(() => sync());
  const sync = () => {
    if (disposed) return;
    const form = field.form;
    if (form !== observedForm) {
      observer.disconnect(); observedForm = form;
      if (form) observer.observe(form, { attributes: true, attributeFilter: ["data-bml-action-pending"] });
    }
    const value = ctx.root.dataset.value ?? JSON.stringify(emptyDocument);
    if (value !== previousValue) { previousValue = value; load(value); }
    field.name = ctx.root.dataset.name ?? "document";
    field.disabled = ctx.root.hasAttribute("data-disabled");
    pending = Boolean(form?.hasAttribute("data-bml-action-pending"));
    field.readOnly = ctx.root.hasAttribute("data-readonly") || field.disabled || pending;
    editor.setEditable(!field.readOnly && !contentError, false);
    target.inert = field.disabled;
    const id = ctx.root.dataset.editorId ?? ctx.id;
    editor.view.dom.setAttribute("role", "textbox");
    editor.view.dom.setAttribute("aria-multiline", "true");
    editor.view.dom.setAttribute("aria-description", "Escape then Tab leaves the editor, including from a table.");
    editor.view.dom.setAttribute("aria-label", ctx.root.dataset.label ?? "Document editor");
    editor.view.dom.setAttribute("aria-readonly", String(!editor.isEditable));
    editor.view.dom.setAttribute("aria-required", String(ctx.root.hasAttribute("data-required")));
    editor.view.dom.setAttribute("aria-describedby", `${id}-hint ${id}-error`);
    editor.view.dom.tabIndex = field.disabled ? -1 : 0;
    toolbar.hidden = ctx.root.hasAttribute("data-readonly") || field.disabled || Boolean(contentError);
    for (const control of insert.querySelectorAll<HTMLInputElement | HTMLButtonElement>("input,button")) control.disabled = field.readOnly;
    if (toolbar.hidden) insert.hidden = true;
    report(); syncToolbar();
    ctx.root.setAttribute("data-document-ready", "");
  };
  const closeInsert = (focusEditor: boolean) => {
    insert.hidden = true;
    url.setCustomValidity("");
    if (focusEditor) editor.commands.focus(); else insertTrigger?.focus();
  };
  const applyInsert = () => {
    if (!editor.isEditable) return;
    const value = url.value.trim();
    let valid = false;
    try { valid = (insertKind === "image" ? ["http:", "https:"] : ["http:", "https:", "mailto:", "tel:"])
      .includes(new URL(value, location.href).protocol); } catch { /* Invalid user URL is reported below. */ }
    url.setCustomValidity(valid && value ? "" : "Enter a valid URL.");
    if (!url.reportValidity()) return;
    const chain = editor.chain().focus().setTextSelection(selection);
    if (insertKind === "link") chain.extendMarkRange("link").setLink({ href: value }).run();
    else chain.setImage({ src: value, alt: ctx.ref<HTMLInputElement>("alt")?.value ?? "" }).run();
    closeInsert(true);
  };
  ctx.on<MouseEvent>(ctx.root, "click", event => {
    const button = event.target instanceof Element ? event.target.closest<HTMLButtonElement>("button") : null;
    if (!button || button.closest("[data-bml-document-editor]") !== ctx.root) return;
    const command = button.dataset.documentCommand;
    if (command && editor.isEditable) {
      if (command === "link" || command === "image") {
        insertKind = command; insertTrigger = button;
        selection = { from: editor.state.selection.from, to: editor.state.selection.to };
        url.value = command === "link" ? String(editor.getAttributes("link").href ?? "") : "";
        url.setCustomValidity(""); insert.hidden = false;
        const altLabel = ctx.ref<HTMLElement>("altLabel"), remove = ctx.ref<HTMLElement>("removeLink");
        if (altLabel) altLabel.hidden = command !== "image";
        if (remove) remove.hidden = command !== "link" || !editor.isActive("link");
        url.focus();
      } else commands[command]?.();
    } else if (button === ctx.ref("apply")) applyInsert();
    else if (button === ctx.ref("cancelInsert")) closeInsert(false);
    else if (button === ctx.ref("removeLink") && editor.isEditable) {
      editor.chain().focus().setTextSelection(selection).extendMarkRange("link").unsetLink().run(); closeInsert(true);
    }
  });
  ctx.on<Event>(toolbar, "change", event => {
    if (!editor.isEditable) return;
    if (event.target === block) {
      const level = Number(block.value);
      if (level >= 1 && level <= 6) editor.chain().focus().setHeading({ level: level as 1 | 2 | 3 | 4 | 5 | 6 }).run();
      else editor.chain().focus().setParagraph().run();
    } else if (event.target === alignment) editor.chain().focus().setTextAlign(alignment.value).run();
  });
  ctx.on<KeyboardEvent>(toolbar, "keydown", event => {
    if (!(event.target instanceof HTMLButtonElement || event.target instanceof HTMLSelectElement)
      || event.altKey || event.ctrlKey || event.metaKey
      || !["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    if (event.target instanceof HTMLSelectElement && ["Home", "End"].includes(event.key)) return;
    const available = controls(), index = available.indexOf(event.target);
    const next = event.key === "Home" ? 0 : event.key === "End" ? available.length - 1
      : (index + (event.key === "ArrowRight" ? 1 : -1) + available.length) % available.length;
    event.preventDefault(); available[next]?.focus();
  });
  ctx.on<FocusEvent>(toolbar, "focusin", syncToolbar);
  ctx.on<KeyboardEvent>(insert, "keydown", event => {
    if (event.key === "Escape") { event.preventDefault(); closeInsert(false); }
    else if (event.key === "Enter" && event.target instanceof HTMLInputElement) { event.preventDefault(); applyInsert(); }
  });
  ctx.on<Event>(url, "input", () => url.setCustomValidity(""));
  // Table's Tab command advances cells and can add rows; reserve an explicit native focus exit.
  ctx.on<KeyboardEvent>(target, "keydown", event => {
    if (event.key === "Escape" && !leaveOnTab) { leaveOnTab = true; event.preventDefault(); event.stopPropagation(); }
    else if (event.key === "Tab" && leaveOnTab) { leaveOnTab = false; event.stopPropagation(); }
    else leaveOnTab = false;
  }, true);
  ctx.on<FocusEvent>(target, "focusout", () => { leaveOnTab = false; });
  ctx.on<Event>(field, "invalid", event => {
    event.preventDefault(); editor.commands.focus();
    feedback.textContent = field.validationMessage; feedback.hidden = false;
    ctx.root.setAttribute("aria-invalid", "true"); editor.view.dom.setAttribute("aria-invalid", "true");
  });
  ctx.on<Event>(document, "reset", event => {
    if (event.target !== field.form) return;
    queueMicrotask(() => { if (!disposed && !event.defaultPrevented) { load(previousValue); sync(); } });
  }, true);
  ctx.onUpdate(sync);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => { if (event.detail.target.contains(ctx.root)) sync(); });
  ctx.onUnmount(() => { disposed = true; observer.disconnect(); editor.destroy(); ctx.root.removeAttribute("data-document-ready"); });
  load(previousValue);
  editor.on("update", publish);
  editor.on("selectionUpdate", syncToolbar);
  editor.on("transaction", syncToolbar);
  sync(); field.hidden = true;
}
