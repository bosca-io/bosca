import { Extension } from "@tiptap/core";
import { PluginKey } from "@tiptap/pm/state";
import Suggestion from "@tiptap/suggestion";
import { mentionMenu, type MentionRange } from "./mention-menu";

/** TipTap owns the active text range; the shared menu owns async results and selection. */
export function documentMentions(root: HTMLElement, id: string) {
  const pluginKey = new PluginKey("documentMentions");
  let menu: ReturnType<typeof mentionMenu> | undefined, disposed = false;
  let refresh = () => {};
  return Extension.create({
    name: "documentMentions",
    priority: 101,
    onFocus() { refresh(); },
    onBlur() { menu?.close(); },
    onDestroy() { disposed = true; menu?.destroy(); },
    addProseMirrorPlugins() {
      const editor = this.editor;
      refresh = () => {
        if (disposed || editor.isDestroyed) return;
        const current: { active: boolean; range: MentionRange; query: string } | undefined = pluginKey.getState(editor.state);
        if (!menu && current?.active) menu = mentionMenu({
          root, field: editor.view.dom, id, kind: "entity",
          enabled: () => editor.isEditable && editor.isFocused && !editor.view.composing,
          select: (choice, range) => {
            const after = editor.state.doc.resolve(range.to).nodeAfter;
            const to = range.to + (after?.isText && after.text?.startsWith(" ") ? 1 : 0);
            editor.chain().focus().insertContentAt({ from: range.from, to }, [
              { type: "mention", attrs: { id: choice.id, label: choice.label,
                entityType: choice.entityType ?? "metadata", mentionSuggestionChar: "@" } },
              { type: "text", text: " " },
            ]).run();
          },
        });
        const decoration = editor.view.dom.querySelector(".document-mention-query");
        menu?.update(current?.active ? { ...current.range, query: current.query } : null,
          decoration?.getBoundingClientRect() ?? null);
      };
      return [Suggestion({
        editor, pluginKey, decorationClass: "document-mention-query",
        // Keep lookup asynchronous work in the shared menu so an obsolete plugin callback cannot publish results.
        items: () => [],
        allow: ({ state, range }) => Boolean(state.doc.resolve(range.from).parent.type.contentMatch.matchType(editor.schema.nodes.mention)),
        render: () => ({ onBeforeStart: refresh, onBeforeUpdate: refresh, onStart: refresh, onUpdate: refresh, onExit: refresh,
          onKeyDown: ({ event }) => menu?.keydown(event) ?? false }),
      })];
    },
  });
}
