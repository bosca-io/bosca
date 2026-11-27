import type { ClientContext, IslandUpdate } from "@bosca/bml";
import { Editor, getSchema, type JSONContent } from "@tiptap/core";
import StarterKit from "@tiptap/starter-kit";
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
import Placeholder from "@tiptap/extension-placeholder";
import { AllSelection, NodeSelection, TextSelection } from "@tiptap/pm/state";
import Collaboration from "@tiptap/extension-collaboration";
import CollaborationCursor from "@tiptap/extension-collaboration-cursor";
import { DragHandle } from "@tiptap/extension-drag-handle";
import { yXmlFragmentToProsemirrorJSON } from "y-prosemirror";
import { DocumentLink, documentExtensions, documentTitle, readDocument } from "./document-schema";
import { documentCodeViews } from "./document-code";
import { documentMentions } from "./document-mentions";
import { documentCollaboration, type DocumentConnectionStatus } from "./document-collaboration";
import { documentContainerOptions, documentContainerViews, documentBibleViews, wrapDocumentContainer } from "./document-containers";
import { documentCommands, type DocumentCommand } from "./document-commands";
import { moveDocumentBlock } from "./document-blocks";
import { documentAction, documentHistoryBoundary } from "./document-history";
import { documentImageViews } from "./document-images";
import { documentSelectionBookmark, type DocumentSelectionBookmark } from "./document-selection";
import { waitForDocumentSync } from "./document-sync";
import type { ImagePickerOpen } from "@bosca/bml-design/images";
import "./document-editor.css";

const emptyDocument: JSONContent = { type: "doc", content: [{ type: "paragraph" }] };

/** Adapt TipTap's document JSON to a native BML form field and component lifecycle. */
export function mountDocumentEditor(ctx: ClientContext) {
  const target = ctx.ref<HTMLDivElement>("editor"), field = ctx.ref<HTMLTextAreaElement>("value");
  const toolbar = ctx.ref<HTMLDivElement>("toolbar"), feedback = ctx.ref<HTMLParagraphElement>("feedback");
  const insert = ctx.ref<HTMLDivElement>("insert"), url = ctx.ref<HTMLInputElement>("url");
  const block = ctx.ref<HTMLSelectElement>("block"), alignment = ctx.ref<HTMLSelectElement>("alignment");
  if (!target || !field || !toolbar || !feedback || !insert || !url || !block || !alignment) return;
  let disposed = false, initialized = false;
  const abort = new AbortController();
  let cleanup = () => abort.abort();
  ctx.onUnmount(() => { disposed = true; cleanup(); ctx.root.removeAttribute("data-document-ready"); });
  ctx.on<Event>(field, "invalid", event => { if (!initialized) event.preventDefault(); });
  const mount = async () => {
    let previousValue = ctx.root.dataset.value ?? JSON.stringify(emptyDocument);
    let contentError = "", saveError = "", pending = false, awaitingSync = false, observedForm: HTMLFormElement | null = null;
    let refreshCollaboration = () => {};
    const revisionField = ctx.ref<HTMLInputElement>("revision");
    const connection = ctx.ref<HTMLElement>("connection");
    const statuses: Record<DocumentConnectionStatus, string> = {
      connecting: "Connecting…", connected: "Connected", offline: "Offline. Changes will sync when connected.",
      denied: "Document access was lost. Sign in or reload to retry.",
    };
    if (ctx.root.dataset.collaborationRoom) {
      // Keep native form validation active while the shared document is still loading.
      field.readOnly = false;
      field.hidden = true;
      field.setCustomValidity("Wait for the document to connect.");
    }
    const collaboration = ctx.root.dataset.collaborationRoom ? documentCollaboration(ctx.root, abort.signal,
      () => refreshCollaboration(), status => {
        ctx.root.dataset.documentConnection = status;
        if (!initialized) field.setCustomValidity(status === "denied" ? "Document access is unavailable." : "Wait for the document to connect.");
        if (connection) { connection.hidden = false; connection.textContent = statuses[status]; }
      }) : null;
    cleanup = () => { abort.abort(); collaboration?.destroy(); };
    if (collaboration) await collaboration.ready;
    if (disposed) return;
    let insertKind: "link" | "image" = "link", insertTrigger: HTMLElement | null = null;
    let selection = { from: 0, to: 0 };
    let leaveOnTab = false;
    let editor: Editor;
    let imageBookmark: DocumentSelectionBookmark | null = null;
    const imagePicker = ctx.root.querySelector<HTMLElement>("[data-document-image-picker]");
    const imageViews = ctx.root.hasAttribute("data-media") ? documentImageViews(ctx.root) : undefined;
    ctx.on(ctx.root, "imagesready", () => imageViews?.reload());
    let containersValue = ctx.root.dataset.containers || "[]";
    let containers = documentContainerOptions(containersValue);
    const referenceRoot = ctx.root.hasAttribute("data-reference-controls") ? ctx.root : undefined;
    const containerViews = documentContainerViews(ctx.root.dataset.editorId || ctx.id, containers, referenceRoot);
    const bibleViews = referenceRoot ? documentBibleViews(referenceRoot) : undefined;
    ctx.on(ctx.root, "documentreferencesready", () => { containerViews.reloadReferences(); bibleViews?.reloadReferences(); });
    const containerSelect = ctx.ref<HTMLSelectElement>("container");
    const codeViews = documentCodeViews(() => {
      const next = Array.from((field.form ?? document).querySelectorAll<HTMLElement>("input,textarea,button,select,a[href],[tabindex]"))
        .find(element => !ctx.root.contains(element) && (ctx.root.compareDocumentPosition(element) & Node.DOCUMENT_POSITION_FOLLOWING)
          && element.tabIndex >= 0 && !element.matches(":disabled") && !element.closest("[inert]") && element.checkVisibility());
      next?.focus();
      return Boolean(next && document.activeElement === next);
    }, { undo: () => editor.commands.undo(), redo: () => editor.commands.redo() });
    const slashCommands = (): DocumentCommand[] => {
      const format = (label: string, command: string): DocumentCommand => ({ label, active: () => editor.isActive(command),
        run: range => { editor.chain().focus().deleteRange(range).run(); commands[command]?.(); } });
      return [
        ...([1, 2, 3, 4, 5, 6] as const).map(level => ({ label: `Heading ${level}`, active: () => editor.isActive("heading", { level }),
          run: (range: { from: number; to: number }) => { editor.chain().focus().deleteRange(range).setHeading({ level }).run(); } })),
        { label: "Paragraph", active: () => editor.isActive("paragraph"),
          run: range => { editor.chain().focus().deleteRange(range).setParagraph().run(); } },
        format("Bold", "bold"), format("Italic", "italic"), format("Strike", "strike"), format("Underline", "underline"),
        format("Superscript", "superscript"), format("Subscript", "subscript"),
        ...(["left", "center", "right", "justify"] as const).map(textAlign => ({
          label: textAlign === "justify" ? "Justify" : `${textAlign[0].toUpperCase()}${textAlign.slice(1)} align`,
          active: () => editor.isActive({ textAlign }),
          run: (range: { from: number; to: number }) => { editor.chain().focus().deleteRange(range).setTextAlign(textAlign).run(); },
        })),
        format("Bullet list", "bulletList"), format("Ordered list", "orderedList"), format("Task list", "taskList"),
        format("Blockquote", "blockquote"), format("Code block", "codeBlock"), format("Divider", "horizontalRule"), format("Table", "table"),
        ...(["link", "image"] as const).map(kind => ({ label: kind === "link" ? "Link" : "Add image", active: () => editor.isActive(kind),
          run: (range: { from: number; to: number }) => {
            if (kind === "image" && imagePicker) openImagePicker(null, range);
            else { editor.chain().focus().deleteRange(range).run(); openInsert(kind); }
          } })),
        { label: "Mermaid diagram", active: () => editor.isActive("codeBlock", { language: "mermaid" }),
          run: range => { editor.chain().focus().deleteRange(range).setCodeBlock({ language: "mermaid" }).run(); } },
        ...containers.map(container => ({ label: `Container: ${container.name || "Content"}`, active: () => editor.isActive("container", { name: container.id }),
          run: (range: { from: number; to: number }) => { editor.chain().focus().deleteRange(range).run(); wrapDocumentContainer(editor, container.id); } })),
      ];
    };
    const dragHandle = ctx.ref<HTMLElement>("dragHandle");
    const extensions = [StarterKit.configure({ document: false, history: collaboration ? false : undefined }),
      ...documentExtensions(ctx.root.dataset.contentExpression || "block+", ctx.root.hasAttribute("data-single-title")),
      DocumentLink.configure({ openOnClick: false, defaultProtocol: "https" }),
      Underline, Subscript, Superscript, Table.configure({ resizable: true }), TableRow, TableHeader, TableCell,
      TaskList, TaskItem.configure({ nested: true }), TextAlign.configure({ types: ["heading", "paragraph"] }),
      ...(ctx.root.hasAttribute("data-mentions") ? [documentMentions(ctx.root, ctx.root.dataset.editorId || ctx.id)] : []),
      documentCommands(ctx.root, ctx.root.dataset.editorId || ctx.id, slashCommands),
      ...(dragHandle ? [DragHandle.configure({
        render: () => { dragHandle.hidden = false; return dragHandle; },
        // Dragging a block keeps the editor's textbox semantics.
        tippyOptions: { aria: { expanded: false, content: null } },
      })] : []),
      Placeholder.configure({ showOnlyCurrent: false, placeholder: ({ node }) => node.type.name === "container" ? ""
        : node.type.name === "heading" && node.attrs.level === 1 && ctx.root.hasAttribute("data-single-title") ? "Title…"
        : ctx.root.dataset.placeholder ?? "Start writing…" }),
      ...(collaboration ? [Collaboration.configure({ document: collaboration.document }),
        CollaborationCursor.configure({ provider: collaboration.provider, user: {
          name: ctx.root.dataset.collaboratorName || "Editor",
          color: /^#[0-9a-f]{6}$/i.test(ctx.root.dataset.collaboratorColor || "") ? ctx.root.dataset.collaboratorColor : "#6366f1",
        } })] : [])];
    if (collaboration) {
      // Validate before constructing the binding, which can normalize away unsupported stored nodes.
      readDocument(JSON.stringify(yXmlFragmentToProsemirrorJSON(collaboration.document.getXmlFragment("default"))), getSchema(extensions));
    }
    editor = new Editor({
      element: target,
      editorProps: {
        nodeViews: { codeBlock: codeViews.codeBlock, container: containerViews.container, ...(bibleViews ? { bible: bibleViews.bible } : {}), ...(imageViews ? { image: imageViews.image } : {}) },
        handleDOMEvents: { beforeinput: (view, event) => {
          if (!view.editable || event.isComposing || event.data === null ||
            !["insertText", "insertReplacementText"].includes(event.inputType) || !(view.state.selection instanceof AllSelection)) return false;
          event.preventDefault();
          view.dispatch(view.state.tr.insertText(event.data).scrollIntoView());
          return true;
        } },
      },
      extensions,
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
      unwrapContainer: () => documentAction(editor.view, () => editor.chain().focus().lift("container").run()),
      moveBlockUp: () => moveDocumentBlock(editor, -1),
      moveBlockDown: () => moveDocumentBlock(editor, 1),
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
        if (!locked && command === "unwrapContainer") control.disabled = !editor.can().lift("container");
        if (!locked && command === "moveBlockUp") control.disabled = !moveDocumentBlock(editor, -1, false);
        if (!locked && command === "moveBlockDown") control.disabled = !moveDocumentBlock(editor, 1, false);
        if (!locked && command === "image" && imagePicker) control.disabled = !imagePicker.hasAttribute("data-image-picker-ready");
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
    const syncSaveStatus = () => {
      const dirty = collaboration?.revisions.dirty ?? field.value !== field.defaultValue;
      ctx.root.toggleAttribute("data-document-dirty", dirty);
      const status = ctx.ref<HTMLElement>("saveStatus");
      if (status) {
        status.hidden = !ctx.root.hasAttribute("data-show-save-status") || ctx.root.hasAttribute("data-readonly");
        status.textContent = awaitingSync ? "Syncing before save…" : dirty ? "Draft" : collaboration?.unsynced ? "Syncing…" : "Saved";
      }
    };
    const report = () => {
      const requiredError = ctx.root.hasAttribute("data-required") && editor.isEmpty ? "Enter document content." : "";
      field.setCustomValidity(contentError || (collaboration?.locked ? "Document access is unavailable." : "") || requiredError);
      feedback.textContent = contentError || saveError || ctx.root.dataset.error || "";
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
      const document = editor.getJSON();
      ctx.root.dispatchEvent(new CustomEvent("documentchange", { bubbles: true,
        detail: { document, title: documentTitle(document), revision: collaboration?.revisions.capture(), dirty: collaboration?.revisions.dirty } }));
      syncSaveStatus(); report();
    };
    const load = (value: string) => {
      try {
        const document = readDocument(value, editor.schema);
        const parsed = editor.schema.nodeFromJSON(document);
        if (!parsed.eq(editor.state.doc)) editor.chain().setContent(document, false, {}, { errorOnInvalidContent: true })
          .setMeta("addToHistory", false).run();
        // Flush template/title normalization before publishing readiness or observing dirty edits.
        editor.view.dispatch(editor.state.tr.setMeta("addToHistory", false));
        value = JSON.stringify(editor.getJSON());
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
      if (value !== previousValue) { previousValue = value; if (!collaboration) load(value); }
      const nextContainers = ctx.root.dataset.containers || "[]";
      if (nextContainers !== containersValue) {
        containers = documentContainerOptions(nextContainers); containersValue = nextContainers;
        containerViews.setDefinitions(containers);
      }
      if (containerSelect && containerSelect.dataset.choices !== containersValue) {
        containerSelect.replaceChildren(new Option("Wrap in container…", ""), ...containers.map(item => new Option(item.name || "Content", item.id)));
        containerSelect.dataset.choices = containersValue;
        const choices = ctx.ref<HTMLElement>("containerChoices");
        if (choices) choices.hidden = !containers.length;
      }
      const saved = ctx.root.dataset.savedRevision;
      if (collaboration && saved && saved !== acknowledgedRevision) {
        acknowledgedRevision = saved; collaboration.revisions.markSaved(saved);
      }
      if (revisionField && collaboration) {
        revisionField.name = `${ctx.root.dataset.name ?? "document"}Revision`;
        revisionField.value = collaboration.revisions.capture();
      }
      if (collaboration) {
        ctx.root.toggleAttribute("data-document-unsynced", collaboration.unsynced);
      }
      field.name = ctx.root.dataset.name ?? "document";
      field.disabled = ctx.root.hasAttribute("data-disabled");
      pending = Boolean(form?.hasAttribute("data-bml-action-pending"));
      field.readOnly = ctx.root.hasAttribute("data-readonly") || field.disabled || pending || awaitingSync;
      editor.setEditable(!field.readOnly && !contentError && !collaboration?.locked, false);
      codeViews.setEditable(editor.isEditable);
      containerViews.setEditable(editor.isEditable);
      bibleViews?.setEditable(editor.isEditable);
      if (imagePicker) imagePicker.toggleAttribute("data-disabled", !editor.isEditable);
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
      syncSaveStatus(); report(); syncToolbar();
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
    const openInsert = (kind: "link" | "image", trigger: HTMLElement | null = null) => {
      insertKind = kind; insertTrigger = trigger;
      selection = { from: editor.state.selection.from, to: editor.state.selection.to };
      url.value = kind === "link" ? String(editor.getAttributes("link").href ?? "") : "";
      url.setCustomValidity(""); insert.hidden = false;
      const altLabel = ctx.ref<HTMLElement>("altLabel"), remove = ctx.ref<HTMLElement>("removeLink");
      if (altLabel) altLabel.hidden = kind !== "image";
      if (remove) remove.hidden = kind !== "link" || !editor.isActive("link");
      url.focus();
    };
    const openImagePicker = (trigger: HTMLElement | null, range?: { from: number; to: number }) => {
      if (!imagePicker || !editor.isEditable || !imagePicker.hasAttribute("data-image-picker-ready")) return;
      imagePicker.dispatchEvent(new Event("imagepickerclose"));
      imageBookmark = documentSelectionBookmark(editor.view, range ? TextSelection.create(editor.state.doc, range.from, range.to) : editor.state.selection);
      const attributes = editor.isActive("image") ? editor.getAttributes("image") : null;
      imagePicker.dispatchEvent(new CustomEvent<ImagePickerOpen>("imagepickeropen", { detail: {
        initial: attributes ? { id: attributes.metadataId || undefined, src: attributes.src || "", alt: attributes.alt || "", title: attributes.title || "" } : undefined,
        select(value) {
          const bookmark = imageBookmark; imageBookmark = null;
          if (disposed || !editor.isEditable || editor.isDestroyed) return;
          const selection = bookmark?.resolve(editor.state.doc);
          if (!selection) {
            feedback.textContent = "The selected image was removed or replaced. Choose an insertion point and try again.";
            feedback.hidden = false; editor.commands.focus(); return;
          }
          const current = selection instanceof NodeSelection && selection.node.type.name === "image" ? selection.node.attrs : null;
          documentAction(editor.view, () => editor.chain().focus().command(({ tr }) => { tr.setSelection(selection); return true; })
            .insertContent({ type: "image", attrs: { ...current, src: value.src, alt: value.alt, title: value.title, metadataId: value.id ?? null } }).run());
        },
        cancel() { imageBookmark = null; if (disposed) return; if (trigger) trigger.focus(); else if (!editor.isDestroyed) editor.commands.focus(); },
      } }));
    };
    const mapImageSelection = ({ transaction }: { transaction: import("@tiptap/pm/state").Transaction }) => {
      if (imageBookmark) imageBookmark = imageBookmark.map(transaction.mapping);
    };
    editor.on("transaction", mapImageSelection);
    ctx.on(ctx.root, "imagepickerready", syncToolbar);
    ctx.on<MouseEvent>(ctx.root, "click", event => {
      const button = event.target instanceof Element ? event.target.closest<HTMLButtonElement>("button") : null;
      if (!button || button.closest("[data-bml-document-editor]") !== ctx.root) return;
      if (button.hasAttribute("data-mention-id")) {
        event.preventDefault();
        ctx.root.dispatchEvent(new CustomEvent("documentmentionactivate", { bubbles: true,
          detail: { id: button.dataset.mentionId, entityType: button.dataset.entityType } }));
        return;
      }
      const command = button.dataset.documentCommand;
      if (command && editor.isEditable) {
        if (command === "link" || command === "image") {
          if (command === "image" && imagePicker) openImagePicker(button); else openInsert(command, button);
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
      else if (containerSelect && event.target === containerSelect) {
        if (containers.some(item => item.id === containerSelect.value)) wrapDocumentContainer(editor, containerSelect.value);
        containerSelect.value = "";
      }
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
      if (event.target instanceof Element && event.target.closest("[data-document-code]")) return;
      if (event.key === "Escape" && editor.view.dom.hasAttribute("aria-controls")) { leaveOnTab = false; return; }
      if (event.key === "Escape" && !leaveOnTab) { leaveOnTab = true; event.preventDefault(); event.stopPropagation(); }
      else if (event.key === "Tab" && leaveOnTab) { leaveOnTab = false; event.stopPropagation(); }
      else leaveOnTab = false;
    }, true);
    ctx.on<FocusEvent>(target, "focusout", () => { leaveOnTab = false; });
    ctx.on<DragEvent>(ctx.root, "dragstart", () => { if (editor.isEditable) documentHistoryBoundary(editor.view); }, true);
    ctx.on<DragEvent>(ctx.root, "dragend", () => { if (editor.isEditable) documentHistoryBoundary(editor.view); });
    ctx.on<Event>(field, "invalid", event => {
      event.preventDefault(); editor.commands.focus();
      feedback.textContent = field.validationMessage; feedback.hidden = false;
      ctx.root.setAttribute("aria-invalid", "true"); editor.view.dom.setAttribute("aria-invalid", "true");
    });
    ctx.on<Event>(document, "reset", event => {
      if (event.target !== field.form) return;
      // A form reset cannot replace a shared CRDT document or erase another editor's work.
      if (collaboration) { event.preventDefault(); return; }
      queueMicrotask(() => { if (!disposed && !event.defaultPrevented) { load(previousValue); sync(); } });
    }, true);
    ctx.on<SubmitEvent>(document, "submit", event => {
      const form = field.form;
      if (!collaboration || event.target !== form || event.defaultPrevented || !form) return;
      if (awaitingSync || collaboration.unsynced) {
        event.preventDefault(); event.stopImmediatePropagation();
        if (awaitingSync) return;
        awaitingSync = true; saveError = ""; sync();
        const submitter = event.submitter;
        void waitForDocumentSync(collaboration.provider, abort.signal).then(() => {
          if (disposed) return;
          if (!form.isConnected || form !== field.form) throw new Error("The document form changed. Save again.");
          awaitingSync = false; sync();
          form.requestSubmit(submitter?.isConnected ? submitter : undefined);
        }).catch(error => {
          if (disposed) return;
          awaitingSync = false;
          saveError = error instanceof Error ? error.message : "Could not sync this document. Save again.";
          sync();
        });
      } else if (saveError) { saveError = ""; report(); }
    }, true);
    ctx.onUpdate(sync);
    ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => { if (event.detail.target.contains(ctx.root)) sync(); });
    cleanup = () => {
      imageBookmark = null;
      imagePicker?.dispatchEvent(new Event("imagepickerclose"));
      editor.off("transaction", mapImageSelection);
      abort.abort(); observer.disconnect(); editor.destroy(); collaboration?.destroy();
    };
    let acknowledgedRevision = "";
    if (collaboration) {
      // Yjs binds the document's children; root attributes stay with the canonical Content body.
      const supplied = readDocument(previousValue, editor.schema);
      editor.view.dispatch(editor.state.tr.setDocAttribute("class", supplied.attrs?.class ?? null).setMeta("addToHistory", false));
      editor.view.dispatch(editor.state.tr.setMeta("addToHistory", false));
      collaboration.revisions.markReady();
      field.value = JSON.stringify(editor.getJSON()); field.defaultValue = field.value;
    } else load(previousValue);
    editor.on("update", publish);
    editor.on("selectionUpdate", syncToolbar);
    editor.on("transaction", syncToolbar);
    refreshCollaboration = sync;
    sync(); field.hidden = true; initialized = true;
  };
  void mount().catch(error => {
    if (disposed) return;
    cleanup();
    field.setCustomValidity("Could not open this document. Reload to retry.");
    feedback.textContent = error instanceof Error ? error.message : "Could not open this document. Reload to retry.";
    feedback.hidden = false; ctx.root.setAttribute("aria-invalid", "true");
  });
}
