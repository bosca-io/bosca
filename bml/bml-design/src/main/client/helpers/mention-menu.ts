import "./mention-menu.css";
import type { MentionChoice, MentionSearch } from "@bosca/bml-design/mentions";
const fieldMenus = new WeakMap<HTMLElement, () => void>();

export interface MentionRange { from: number; to: number; query: string }

type MentionMenuOptions = {
  root: HTMLElement;
  field: HTMLElement;
  id: string;
  kind: MentionSearch["kind"];
  enabled(): boolean;
  select(choice: MentionChoice, range: MentionRange): void;
};

/** Shared, application-independent suggestions for native comments and rich documents. */
export function mentionMenu(options: MentionMenuOptions) {
  const { root, field, id, kind } = options;
  fieldMenus.get(field)?.();
  const panel = document.createElement("div"), list = document.createElement("div"), status = document.createElement("p");
  panel.className = "bml-mention-menu";
  panel.hidden = true;
  list.id = `${id}-mentions`;
  list.setAttribute("role", "listbox");
  list.setAttribute("aria-label", kind === "profile" ? "Profile mentions" : "Document mentions");
  status.setAttribute("role", "status");
  status.setAttribute("aria-live", "polite");
  panel.append(list, status);
  root.append(panel);
  root.classList.add("bml-mention-host");
  const attributes = ["aria-autocomplete", "aria-haspopup", "aria-controls", "aria-activedescendant"];
  const previous = new Map(attributes.map(name => [name, field.getAttribute(name)]));
  let range: MentionRange | null = null, dismissed = "", choices: readonly MentionChoice[] = [], active = 0;
  let timer: ReturnType<typeof setTimeout> | undefined, request: AbortController | undefined;
  let disposed = false, rectangle: DOMRect | null = null;
  const key = (value: MentionRange) => `${value.from}:${value.to}:${value.query}`;
  const restoreAttributes = () => {
    if (field.getAttribute("aria-controls") !== list.id) return;
    for (const [name, value] of previous) value === null ? field.removeAttribute(name) : field.setAttribute(name, value);
  };
  const cancel = () => { clearTimeout(timer); request?.abort(); request = undefined; };
  const close = () => {
    cancel(); range = null; choices = []; active = 0; panel.hidden = true;
    list.replaceChildren(); status.textContent = ""; restoreAttributes();
  };
  const position = () => {
    if (panel.hidden) return;
    const host = root.getBoundingClientRect(), anchor = rectangle ?? field.getBoundingClientRect();
    const top = panel.offsetHeight > window.innerHeight - anchor.bottom - 8 && anchor.top > panel.offsetHeight + 8
      ? anchor.top - panel.offsetHeight - 4 : anchor.bottom + 4;
    panel.style.top = `${top - host.top + root.scrollTop}px`;
    panel.style.left = `${Math.max(0, Math.min(anchor.left - host.left, host.width - Math.min(host.width, 320)))}px`;
  };
  const selected = () => {
    const choice = choices[active], current = range;
    if (!choice || !current || !options.enabled()) { close(); return; }
    close();
    options.select(choice, current);
  };
  const render = (message: string) => {
    list.replaceChildren();
    status.textContent = message;
    status.hidden = !message;
    choices.forEach((choice, index) => {
      const option = document.createElement("div"), label = document.createElement("span");
      option.id = `${list.id}-${index}`;
      option.setAttribute("role", "option");
      option.setAttribute("aria-selected", String(index === active));
      option.dataset.mentionIndex = String(index);
      label.textContent = choice.label;
      option.append(label);
      if (choice.handle || choice.entityType) {
        const detail = document.createElement("small");
        detail.textContent = choice.handle ? `@${choice.handle}` : choice.entityType!;
        option.append(detail);
      }
      list.append(option);
    });
    const current = list.children[active];
    if (current) field.setAttribute("aria-activedescendant", current.id);
    else field.removeAttribute("aria-activedescendant");
    position();
    if (current instanceof HTMLElement) {
      if (current.offsetTop < list.scrollTop) list.scrollTop = current.offsetTop;
      else if (current.offsetTop + current.offsetHeight > list.scrollTop + list.clientHeight)
        list.scrollTop = current.offsetTop + current.offsetHeight - list.clientHeight;
    }
  };
  const update = (next: MentionRange | null, anchor: DOMRect | null = null) => {
    if (disposed) return;
    rectangle = anchor;
    if (!options.enabled() || !next || (kind === "entity" && !next.query)) { dismissed = ""; close(); return; }
    if (dismissed === key(next)) return;
    if (range && key(range) === key(next)) { position(); return; }
    cancel();
    range = { ...next }; choices = []; active = 0; panel.hidden = false;
    field.setAttribute("aria-autocomplete", "list");
    field.setAttribute("aria-haspopup", "listbox");
    field.setAttribute("aria-controls", list.id);
    render("Searching…");
    const controller = request = new AbortController();
    timer = setTimeout(() => {
      let results: readonly MentionChoice[] | Promise<readonly MentionChoice[]> | undefined;
      let responded = false;
      const detail: MentionSearch = { query: next.query, kind, signal: controller.signal, respondWith(value) {
        if (responded) throw new Error("Mention search already has a response.");
        responded = true; results = value;
      } };
      root.dispatchEvent(new CustomEvent<MentionSearch>("mentionsearch", { bubbles: true, detail }));
      const response = responded ? Promise.resolve(results!) : Promise.reject(new Error("Mention search is unavailable."));
      void response.then(rows => {
        if (disposed || controller.signal.aborted) return;
        if (!options.enabled()) { close(); return; }
        if (!Array.isArray(rows)) throw new Error("Mention search returned invalid choices.");
        const seen = new Set<string>();
        choices = rows.filter(row => {
          if (!row || typeof row.id !== "string" || !row.id || typeof row.label !== "string" || !row.label.trim()) return false;
          if (kind === "profile" && (typeof row.handle !== "string" || !/^[A-Za-z0-9._-]+$/.test(row.handle))) return false;
          const identity = `${row.entityType ?? "profile"}:${row.id}`;
          if (seen.has(identity)) return false;
          seen.add(identity); return true;
        }).slice(0, kind === "profile" ? 8 : 20);
        render(choices.length ? "" : "No mentions found.");
      }).catch(error => {
        if (disposed || controller.signal.aborted) return;
        if (!options.enabled()) { close(); return; }
        choices = []; render("Could not load mentions. Edit the query to retry.");
        root.dispatchEvent(new CustomEvent("mentionsearcherror", { bubbles: true, detail: { query: next.query, kind, error } }));
      });
    }, 150);
  };
  const keydown = (event: KeyboardEvent) => {
    if (panel.hidden || !range || event.isComposing || event.ctrlKey || event.metaKey || event.altKey) return false;
    if (!options.enabled()) { close(); return false; }
    if (event.key === "Escape") { dismissed = key(range); close(); return true; }
    if (event.key === "ArrowUp" || event.key === "ArrowDown") {
      const next = active + (event.key === "ArrowDown" ? 1 : -1);
      active = kind === "entity" && choices.length ? (next + choices.length) % choices.length
        : Math.max(0, Math.min(choices.length - 1, next));
      render(status.textContent ?? ""); return true;
    }
    if (event.key === "Enter" && choices.length) { selected(); return true; }
    if (event.key === "Tab") close();
    return false;
  };
  const pointer = (event: MouseEvent) => {
    const option = (event.target as Element).closest<HTMLElement>("[data-mention-index]");
    if (!option || !list.contains(option)) return;
    event.preventDefault(); active = Number(option.dataset.mentionIndex); selected();
  };
  const retainFocus = (event: Event) => event.preventDefault();
  const blur = () => { dismissed = ""; close(); };
  panel.addEventListener("mousedown", retainFocus);
  panel.addEventListener("click", pointer);
  field.addEventListener("blur", blur);
  const observer = new MutationObserver(() => { if (!options.enabled()) close(); });
  observer.observe(field, { attributes: true, attributeFilter: ["contenteditable", "readonly", "disabled", "aria-readonly", "aria-disabled"] });
  document.addEventListener("scroll", position, true);
  window.addEventListener("resize", position);
  const destroy = () => {
    if (disposed) return;
    disposed = true; close(); panel.remove(); root.classList.remove("bml-mention-host");
    observer.disconnect();
    panel.removeEventListener("mousedown", retainFocus); panel.removeEventListener("click", pointer);
    field.removeEventListener("blur", blur); document.removeEventListener("scroll", position, true);
    window.removeEventListener("resize", position);
    if (fieldMenus.get(field) === destroy) fieldMenus.delete(field);
  };
  fieldMenus.set(field, destroy);
  return { update, keydown, close, destroy };
}
