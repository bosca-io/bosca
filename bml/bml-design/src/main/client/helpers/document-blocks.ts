import type { Editor } from "@tiptap/core";
import { Fragment, type Node as DocumentNode } from "@tiptap/pm/model";
import { Selection } from "@tiptap/pm/state";
import { documentAction } from "./document-history";

/** Move a whole top-level block while preserving its attributes and the cursor inside it. */
export function moveDocumentBlock(editor: Editor, direction: -1 | 1, dispatch = true) {
  if (!editor.isEditable) return false;
  const { doc, selection } = editor.state;
  const index = selection.$from.index(0), other = index + direction;
  if (other < 0 || other >= doc.childCount) return false;
  const nodes: DocumentNode[] = [], positions: number[] = [];
  doc.forEach((node, offset) => { nodes.push(node); positions.push(offset); });
  [nodes[index], nodes[other]] = [nodes[other], nodes[index]];
  if (!doc.type.validContent(Fragment.fromArray(nodes))) return false;
  if (!dispatch) return true;
  const from = positions[index], block = doc.child(index);
  const destination = direction < 0 ? positions[other] : positions[other] + doc.child(other).nodeSize - block.nodeSize;
  documentAction(editor.view, () => {
    const transaction = editor.state.tr.delete(from, from + block.nodeSize).insert(destination, block);
    const cursor = Math.min(transaction.doc.content.size, destination + selection.from - from);
    editor.view.dispatch(transaction.setSelection(Selection.near(transaction.doc.resolve(cursor))).scrollIntoView());
  });
  editor.view.focus(); return true;
}
