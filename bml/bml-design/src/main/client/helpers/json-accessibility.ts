import type { ClientContext, IslandUpdate } from "@bosca/bml";

/** Gives the structured editor's content and controls their native tree/table semantics. */
export function attachJsonAccessibility(ctx: ClientContext, target: HTMLElement) {
  const attribute = (element: Element, name: string, value: string) => {
    if (element.getAttribute(name) !== value) element.setAttribute(name, value);
  };
  const sync = () => {
    const label = ctx.root.dataset.label ?? "JSON editor";
    for (const tree of target.querySelectorAll<HTMLElement>(".jse-tree-mode")) {
      attribute(tree, "role", "group");
      const content = tree.querySelector(".jse-contents");
      if (content) { attribute(content, "role", "tree"); attribute(content, "aria-label", label); }
      for (const group of tree.querySelectorAll(".jse-props")) attribute(group, "role", "group");
      for (const node of tree.querySelectorAll(".jse-json-node")) {
        if (node.classList.contains("jse-expanded")) attribute(node, "aria-expanded", "true");
        else if (node.classList.contains("jse-collapsed")) attribute(node, "aria-expanded", "false");
      }
    }
    for (const table of target.querySelectorAll(".jse-table-mode")) {
      attribute(table, "role", "group");
      const content = table.querySelector<HTMLTableElement>("table");
      if (content) {
        attribute(content, "aria-label", label);
        const rows = Array.from(content.rows).filter(row => row.classList.contains("jse-table-row"));
        const spacers = Array.from(content.rows).filter(row => !row.classList.contains("jse-table-row"));
        for (const spacer of spacers) attribute(spacer, "aria-hidden", "true");
        const indices = rows.map(row => row.classList.contains("jse-table-row-header") ? 1 :
          Number.parseInt(row.querySelector(".jse-table-cell-gutter")?.firstChild?.textContent ?? "", 10) + 2);
        if (indices.every((index, position) => Number.isSafeInteger(index) && index > (indices[position - 1] ?? 0))) {
          // Spacer heights indicate omitted rows, whose total cannot be inferred from this DOM window.
          const virtual = spacers.some(row => Array.from(row.cells).some(cell => Number.parseFloat(cell.style.height) > 0));
          attribute(content, "aria-rowcount", virtual ? "-1" : String(rows.length));
          rows.forEach((row, index) => attribute(row, "aria-rowindex", String(indices[index])));
        } else {
          content.removeAttribute("aria-rowcount");
          rows.forEach(row => row.removeAttribute("aria-rowindex"));
        }
      }
      for (const header of table.querySelectorAll(".jse-table-row-header > th")) attribute(header, "scope", "col");
      for (const header of table.querySelectorAll(".jse-table-cell-gutter")) attribute(header, "scope", "row");
      for (const header of table.querySelectorAll(".jse-table-row-header > th:first-child")) {
        if (!header.textContent?.trim()) {
          const name = document.createElement("span");
          name.className = "json-row-label";
          name.textContent = "Row";
          header.append(name);
        }
      }
    }
    for (const button of target.querySelectorAll(".jse-navigation-bar-arrow")) {
      const path = button.parentElement?.querySelector("button:not(.jse-navigation-bar-arrow)")?.textContent?.trim() || "document root";
      attribute(button, "aria-label", `Choose property in ${path}`);
    }
    for (const icon of target.querySelectorAll('svg[role="presentation"][aria-label=""]')) {
      icon.removeAttribute("aria-label");
      attribute(icon, "aria-hidden", "true");
    }
  };
  const observer = new MutationObserver(sync);
  observer.observe(target, { childList: true, characterData: true, subtree: true, attributes: true, attributeFilter: ["class", "role", "aria-label", "aria-expanded"] });
  ctx.onUpdate(sync);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => {
    if (event.detail.target.contains(ctx.root)) sync();
  });
  ctx.onUnmount(() => observer.disconnect());
  sync();
}
