import { Extension, type Range } from "@tiptap/core";
import { PluginKey } from "@tiptap/pm/state";
import Suggestion from "@tiptap/suggestion";
import { documentAction } from "./document-history";

export type DocumentCommand = { label: string; run(range: Range): void; active(): boolean };

/** Slash commands use the same transactions and application adapters as the formatting toolbar. */
export function documentCommands(root: HTMLElement, id: string, commands: () => readonly DocumentCommand[]) {
  const pluginKey = new PluginKey("documentCommands");
  let panel: HTMLElement | undefined, field: HTMLElement | undefined, active = 0, previous = "";
  let range: Range | undefined, choices: readonly DocumentCommand[] = [], disposed = false, dismissed = "";
  const close = () => {
    if (panel) panel.hidden = true;
    if (field?.getAttribute("aria-controls") === `${id}-commands`) {
      field.removeAttribute("aria-controls"); field.removeAttribute("aria-activedescendant");
      field.removeAttribute("aria-autocomplete"); field.removeAttribute("aria-haspopup");
    }
  };
  let refresh = () => {};
  let cleanup = () => {};
  return Extension.create({
    name: "documentCommands",
    priority: 102,
    onBlur() { close(); },
    onFocus() { refresh(); },
    onDestroy() { disposed = true; cleanup(); close(); panel?.remove(); },
    onCreate() {
      const observer = new MutationObserver(() => { if (!this.editor.isEditable) close(); });
      observer.observe(this.editor.view.dom, { attributes: true, attributeFilter: ["contenteditable"] });
      const position = () => { if (panel && !panel.hidden) refresh(); };
      document.addEventListener("scroll", position, true); window.addEventListener("resize", position);
      cleanup = () => { observer.disconnect(); document.removeEventListener("scroll", position, true); window.removeEventListener("resize", position); };
    },
    addProseMirrorPlugins() {
      const editor = this.editor;
      const select = (index: number) => {
        const command = choices[index], current = range;
        if (disposed || editor.isDestroyed || !command || !current || !editor.isEditable) return;
        close(); documentAction(editor.view, () => command.run(current));
      };
      const render = () => {
        if (!panel || !field) return;
        panel.replaceChildren();
        if (!choices.length) { const empty = document.createElement("p"); empty.textContent = "No commands found."; panel.append(empty); }
        choices.forEach((command, index) => {
          const option = document.createElement("div");
          option.id = `${id}-command-${index}`;
          option.setAttribute("role", "option"); option.setAttribute("aria-selected", String(index === active));
          option.textContent = command.label; option.dataset.active = String(command.active());
          option.addEventListener("mousedown", event => event.preventDefault());
          option.addEventListener("click", () => select(index)); panel!.append(option);
        });
        const selected = panel.children[active];
        if (selected && choices.length) {
          field.setAttribute("aria-activedescendant", selected.id);
          if (selected instanceof HTMLElement) {
            if (selected.offsetTop < panel.scrollTop) panel.scrollTop = selected.offsetTop;
            else if (selected.offsetTop + selected.offsetHeight > panel.scrollTop + panel.clientHeight)
              panel.scrollTop = selected.offsetTop + selected.offsetHeight - panel.clientHeight;
          }
        } else field.removeAttribute("aria-activedescendant");
      };
      refresh = () => {
        if (disposed || editor.isDestroyed) return;
        const state: { active: boolean; range: Range; query: string } | undefined = pluginKey.getState(editor.state);
        if (!state?.active || !editor.isEditable || !editor.isFocused || editor.view.composing) {
          if (!state?.active) dismissed = "";
          close(); return;
        }
        const key = `${state.range.from}:${state.range.to}:${state.query}`;
        if (dismissed === key) return;
        if (!panel) {
          panel = document.createElement("div"); panel.id = `${id}-commands`; panel.className = "document-commands";
          panel.setAttribute("role", "listbox"); panel.setAttribute("aria-label", "Document commands");
          root.append(panel); field = editor.view.dom;
        }
        if (previous !== key) active = 0;
        previous = key; range = state.range;
        choices = commands().filter(item => item.label.toLowerCase().startsWith(state.query.toLowerCase()));
        panel.hidden = false; field!.setAttribute("aria-controls", panel.id);
        field!.setAttribute("aria-autocomplete", "list"); field!.setAttribute("aria-haspopup", "listbox");
        render();
        const host = root.getBoundingClientRect();
        const anchor = editor.view.dom.querySelector(".document-command-query")?.getBoundingClientRect() ?? field!.getBoundingClientRect();
        panel.style.width = `${Math.max(0, Math.min(280, host.width, window.innerWidth - 16))}px`;
        panel.style.left = `${Math.max(8, Math.min(anchor.left, window.innerWidth - panel.offsetWidth - 8))}px`;
        const top = panel.offsetHeight > window.innerHeight - anchor.bottom - 8 && anchor.top > panel.offsetHeight + 8
          ? anchor.top - panel.offsetHeight - 4 : anchor.bottom + 4;
        panel.style.top = `${Math.max(8, Math.min(top, window.innerHeight - panel.offsetHeight - 8))}px`;
      };
      return [Suggestion({ editor, pluginKey, char: "/", decorationClass: "document-command-query", items: () => [],
        allow: ({ state, range }) => {
          const parent = state.doc.resolve(range.from).parent;
          return parent.isTextblock && parent.type.name !== "codeBlock" && !(parent.type.name === "heading" && parent.attrs.level === 1);
        },
        render: () => ({ onBeforeStart: refresh, onBeforeUpdate: refresh, onStart: refresh, onUpdate: refresh, onExit: refresh,
          onKeyDown: ({ event }) => {
            if (!panel || panel.hidden || !editor.isEditable || event.isComposing || event.ctrlKey || event.metaKey || event.altKey) return false;
            if (event.key === "Escape") { dismissed = previous; close(); return true; }
            const direction = ["ArrowDown", "ArrowRight"].includes(event.key) ? 1 : ["ArrowUp", "ArrowLeft"].includes(event.key) ? -1 : 0;
            if (direction) { active = choices.length ? (active + direction + choices.length) % choices.length : 0; render(); return true; }
            if (event.key === "Enter" && choices.length) { select(active); return true; }
            if (event.key === "Tab") close();
            return false;
          },
        }),
      })];
    },
  });
}
