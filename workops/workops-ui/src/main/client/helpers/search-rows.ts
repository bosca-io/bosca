import type { ClientContext } from "@bosca/bml";

/** WorkOps shortcuts move through the current task or specification result page's native links. */
export function workOpsSearchRows(ctx: ClientContext, kind: "task" | "spec" = "task") {
  const rows = [...ctx.root.querySelectorAll<HTMLElement>(`[data-workops-${kind}-row]`)];
  if (!rows.length) return;
  let selected = -1;
  ctx.on<KeyboardEvent>(document, "keydown", event => {
    if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey || event.isComposing || !ctx.root.isConnected) return;
    const target = event.target;
    if (target instanceof HTMLElement && target.isContentEditable || target instanceof Element && target.closest('input, textarea, select, dialog[open], [role="textbox"], [role="dialog"], [role="combobox"], [role="listbox"], [role="menu"]')) return;
    if (event.key === "j" || event.key === "k") {
      event.preventDefault(); rows[selected]?.removeAttribute("data-selected");
      selected = event.key === "j" ? Math.min(selected + 1, rows.length - 1) : Math.max(selected - 1, 0);
      const row = rows[selected]; row.setAttribute("data-selected", "");
      row.querySelector<HTMLElement>(`[data-workops-${kind}-link]`)?.focus(); row.scrollIntoView({ block: "nearest" });
    } else if (event.key === "Enter" && selected >= 0) {
      const row = rows[selected];
      if (target instanceof Element && target.closest('button, a, summary') && !row.contains(target)) return;
      event.preventDefault(); row.querySelector<HTMLAnchorElement>(`[data-workops-${kind}-link]`)?.click();
    }
  });
}
