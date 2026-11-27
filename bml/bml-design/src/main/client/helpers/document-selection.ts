import type { Node as DocumentNode } from "@tiptap/pm/model";
import { AllSelection, NodeSelection, TextSelection, type Selection } from "@tiptap/pm/state";
import type { Mappable } from "@tiptap/pm/transform";
import type { EditorView } from "@tiptap/pm/view";
import { absolutePositionToRelativePosition, relativePositionToAbsolutePosition, ySyncPluginKey, type ProsemirrorBinding } from "y-prosemirror";

export interface DocumentSelectionBookmark {
  map(mapping: Mappable): DocumentSelectionBookmark;
  resolve(doc: DocumentNode): Selection | null;
}

/** Keep a delayed editor action attached to the same shared content across Yjs node replacements. */
export function documentSelectionBookmark(view: EditorView, selection = view.state.selection): DocumentSelectionBookmark {
  let plain = selection.getBookmark();
  const binding: ProsemirrorBinding | undefined = ySyncPluginKey.getState(view.state)?.binding;
  const anchor = binding ? absolutePositionToRelativePosition(selection.anchor, binding.type, binding.mapping) : null;
  const head = binding ? absolutePositionToRelativePosition(selection.head, binding.type, binding.mapping) : null;
  let nodePosition = selection.from, removed = false;
  const saved: DocumentSelectionBookmark = {
    map(mapping) {
      if (!binding && selection instanceof NodeSelection) {
        const mapped = mapping.mapResult(nodePosition); removed ||= mapped.deleted; nodePosition = mapped.pos;
      }
      plain = plain.map(mapping); return saved;
    },
    resolve(doc: DocumentNode) {
      if (removed) return null;
      if (!binding || !anchor || !head) return plain.resolve(doc);
      if (selection instanceof AllSelection) return new AllSelection(doc);
      const from = relativePositionToAbsolutePosition(binding.doc, binding.type, anchor, binding.mapping);
      const to = relativePositionToAbsolutePosition(binding.doc, binding.type, head, binding.mapping);
      if (from === null || to === null) return selection instanceof NodeSelection ? null : plain.resolve(doc);
      if (selection instanceof NodeSelection) {
        const node = doc.nodeAt(from);
        return node?.type === selection.node.type && to - from === node.nodeSize && NodeSelection.isSelectable(node)
          ? NodeSelection.create(doc, from) : null;
      }
      return TextSelection.between(doc.resolve(from), doc.resolve(to));
    },
  };
  return saved;
}
