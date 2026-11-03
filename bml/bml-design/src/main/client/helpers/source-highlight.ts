import { HighlightStyle } from "@codemirror/language";
import { tags } from "@lezer/highlight";

export const sourceHighlight = HighlightStyle.define([
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
