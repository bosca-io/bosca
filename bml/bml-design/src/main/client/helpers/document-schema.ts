import { Extension, Node, type JSONContent } from "@tiptap/core";
import Image from "@tiptap/extension-image";
import Link from "@tiptap/extension-link";
import { Plugin } from "@tiptap/pm/state";
import type { Schema } from "@tiptap/pm/model";

function storedAttributes(element: HTMLElement) {
  try {
    const attributes: unknown = JSON.parse(element.dataset.documentAttributes || "{}");
    return attributes !== null && typeof attributes === "object" && !Array.isArray(attributes) ? attributes : false;
  } catch { return false; }
}

/** Content's stored ProseMirror schema, shared by document consumers. */
const Container = Node.create({
  name: "container", group: "block", content: "block*", defining: true,
  addAttributes: () => Object.fromEntries(["name", "references", "metadataId", "version", "renderer"]
    .map(name => [name, { default: null }])),
  parseHTML: () => [{ tag: "section[data-document-container]", getAttrs: storedAttributes,
    contentElement: ".document-container-content" }],
  renderHTML: ({ node }) => ["section", { "data-document-container": node.attrs.name ?? "",
    "data-document-attributes": JSON.stringify(node.attrs) },
    ["div", { class: "document-container-label", contenteditable: "false" }, String(node.attrs.name ?? "Content")],
    ["div", { class: "document-container-content" }, 0]],
});

const Bible = Node.create({
  name: "bible", group: "block", atom: true, draggable: true,
  addAttributes: () => Object.fromEntries(["usfm", "metadataIds", "metadataId", "references"].map(name => [name, { default: null }])),
  parseHTML: () => [{ tag: "div[data-document-bible]", getAttrs: storedAttributes }],
  renderHTML: ({ node }) => ["div", { "data-document-bible": "", contenteditable: "false",
    "data-document-attributes": JSON.stringify(node.attrs) },
    Array.isArray(node.attrs.usfm ?? node.attrs.references) && (node.attrs.usfm ?? node.attrs.references).length
      ? (node.attrs.usfm ?? node.attrs.references).join(", ") : "Scripture references"],
});

const Mention = Node.create({
  name: "mention", group: "inline", inline: true, atom: true,
  addAttributes: () => ({ id: { default: null }, label: { default: null }, entityType: { default: "metadata" },
    mentionSuggestionChar: { default: null } }),
  parseHTML: () => [{ tag: "[data-mention-id]", getAttrs: element => element.hasAttribute("data-document-attributes")
    ? storedAttributes(element) : { id: element.getAttribute("data-mention-id"), label: element.textContent?.replace(/^@/, ""),
      entityType: element.getAttribute("data-entity-type") || "metadata" } }],
  renderHTML: ({ node }) => ["button", { type: "button", class: "document-mention", contenteditable: "false",
    "data-mention-id": node.attrs.id ?? "", "data-entity-type": node.attrs.entityType ?? "metadata",
    "data-document-attributes": JSON.stringify(node.attrs) },
    `@${node.attrs.label || "Mention"}`],
  renderText: ({ node }) => `@${node.attrs.label || "Mention"}`,
});

export const DocumentImage = Image.extend({
  addAttributes() { return { ...this.parent?.(), metadataId: { default: null } }; },
});

export const DocumentLink = Link.extend({
  addAttributes() { return { ...this.parent?.(), url: { default: null,
    parseHTML: element => element.getAttribute("data-document-url"),
    renderHTML: attributes => attributes.url ? { "data-document-url": attributes.url } : {},
  } }; },
});

/** Select the template expression and normalize references and optional later H1s. */
export function documentExtensions(contentExpression: string, singleTitle: boolean) {
  return [Node.create({ name: "doc", topNode: true, content: contentExpression }), Container, Bible, Mention,
    DocumentImage, Extension.create({
      name: "documentClasses",
      addGlobalAttributes: () => [{
        types: ["doc", "paragraph", "heading", "blockquote", "bulletList", "orderedList", "listItem", "taskList", "taskItem",
          "horizontalRule", "hardBreak", "codeBlock", "table", "tableRow", "tableCell", "tableHeader", "image", "container", "bible", "mention"],
        attributes: { class: { default: null, parseHTML: element => {
          if (!element.hasAttribute("data-document-attributes")) return element.getAttribute("class");
          const attributes = storedAttributes(element);
          return attributes && "class" in attributes ? attributes.class : null;
        } } },
      }],
    }), Extension.create({
      name: "documentTitle",
      addProseMirrorPlugins: () => [new Plugin({
        appendTransaction: (_transactions, _oldState, state) => {
          const transaction = state.tr;
          let titleSeen = false;
          state.doc.descendants((node, position) => {
            if (node.type.name === "container" && node.attrs.references != null) {
              const references: unknown = node.attrs.references;
              const normalized = Array.isArray(references) ? references.filter((value: unknown) => typeof value === "string" && value.length > 0) : [];
              if (!Array.isArray(references) || references.length !== normalized.length) {
                transaction.setNodeMarkup(position, undefined, { ...node.attrs, references: normalized });
              }
            }
            if (!singleTitle || node.type.name !== "heading" || node.attrs.level !== 1) return;
            if (titleSeen) transaction.setNodeMarkup(position, undefined, { ...node.attrs, level: 2 });
            titleSeen = true;
          });
          return transaction.docChanged ? transaction : null;
        },
      })],
    })];
}

/** Match Content/Studio normalization without changing the caller's original object. */
export function sanitizeDocument(document: JSONContent): JSONContent {
  const result = { ...document };
  if (document.type === "container" && document.attrs && document.attrs.references != null) {
    result.attrs = { ...document.attrs, references: Array.isArray(document.attrs.references)
      ? document.attrs.references.filter((value: unknown) => typeof value === "string" && value.length > 0) : [] };
  }
  if (document.content) result.content = document.content
    .filter(child => child.type !== "text" || Boolean(child.text?.length)).map(sanitizeDocument);
  return result;
}

/** Reject unrecognized data before ProseMirror can drop it during parsing. */
export function readDocument(value: string, schema: Schema): JSONContent {
  const document: JSONContent = JSON.parse(value);
  if (!document || document.type !== "doc") throw new Error("Expected a ProseMirror doc object.");
  const check = (node: JSONContent) => {
    const type = node.type ? schema.nodes[node.type] : undefined;
    if (!type) throw new Error(`Unsupported document node: ${node.type ?? "missing type"}.`);
    if (["doc", "blockquote", "hardBreak", "horizontalRule"].includes(node.type || "") && node.attrs?.type === "empty") {
      const { type: _discriminator, ...attributes } = node.attrs;
      node.attrs = attributes;
    }
    for (const attribute of Object.keys(node.attrs ?? {})) {
      if (!Object.hasOwn(type.spec.attrs ?? {}, attribute)) throw new Error(`Unsupported ${node.type} attribute: ${attribute}.`);
    }
    for (const mark of node.marks ?? []) {
      const type = schema.marks[mark.type];
      if (!type) throw new Error(`Unsupported document mark: ${mark.type}.`);
      for (const attribute of Object.keys(mark.attrs ?? {})) {
        if (!Object.hasOwn(type.spec.attrs ?? {}, attribute)) throw new Error(`Unsupported ${mark.type} attribute: ${attribute}.`);
      }
    }
    node.content?.forEach(check);
  };
  check(document);
  const normalized = sanitizeDocument(document);
  schema.nodeFromJSON(normalized).check();
  return normalized;
}

export function documentTitle(document: JSONContent): string {
  const text = (node: JSONContent): string => node.type === "mention" ? `@${node.attrs?.label || "Mention"}`
    : node.text ?? (node.content ?? []).map(text).join("");
  const find = (node: JSONContent): string | undefined => {
    if (node.type === "heading" && node.attrs?.level === 1) return text(node);
    for (const child of node.content ?? []) {
      const title = find(child);
      if (title !== undefined) return title;
    }
    return undefined;
  };
  return find(document) ?? "";
}
