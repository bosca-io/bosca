import type { EditorView } from "@tiptap/pm/view";
import { closeHistory } from "@tiptap/pm/history";
import { yUndoPluginKey } from "y-prosemirror";

const active = new WeakSet<EditorView>();

function boundary(view: EditorView) {
  yUndoPluginKey.getState(view.state)?.undoManager.stopCapturing();
  view.dispatch(closeHistory(view.state.tr));
}

/** Keep an explicit document action separate from typing in plain and shared histories. */
export function documentAction<T>(view: EditorView, action: () => T): T {
  if (active.has(view)) return action();
  active.add(view);
  try { boundary(view); return action(); }
  finally { boundary(view); active.delete(view); }
}

/** Separate a native drag gesture from adjacent edits. */
export function documentHistoryBoundary(view: EditorView) { boundary(view); }
