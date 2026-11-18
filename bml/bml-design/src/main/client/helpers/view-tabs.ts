import type { ClientContext, IslandUpdate } from "@bosca/bml";

/** Emits local view choices; the caller owns panel relationships and visibility. */
export function viewTabs(ctx: ClientContext) {
  const tabs = () => [...ctx.root.querySelectorAll<HTMLButtonElement>(":scope > [role=tab]")];
  let serverValue: string | undefined;
  const mark = (value: string) => {
    const buttons = tabs();
    const selected = buttons.find(button => button.dataset.tab === value);
    const focusable = selected && !selected.disabled ? selected : buttons.find(button => !button.disabled);
    for (const button of buttons) {
      button.setAttribute("aria-selected", String(button === selected));
      button.tabIndex = button === focusable ? 0 : -1;
    }
  };
  const sync = () => {
    const metadata = new Map((JSON.parse(ctx.root.dataset.tabs ?? "[]") as { value: string; label: string; disabled?: boolean }[])
      .map(tab => [tab.value, tab]));
    for (const button of tabs()) {
      const tab = metadata.get(button.dataset.tab ?? "");
      if (!tab) continue;
      button.disabled = Boolean(tab.disabled);
      button.textContent = tab.label;
    }
    const next = ctx.root.dataset.selected ?? "";
    if (serverValue !== next) mark(next);
    else mark(ctx.root.querySelector<HTMLButtonElement>('[aria-selected="true"]')?.dataset.tab ?? next);
    serverValue = next;
  };
  const select = (button: HTMLButtonElement) => {
    mark(button.dataset.tab ?? "");
    ctx.root.dispatchEvent(new CustomEvent("viewchange", { bubbles: true, detail: button.dataset.tab }));
  };
  ctx.on<MouseEvent>(ctx.root, "click", event => {
    const button = event.target instanceof Element ? event.target.closest<HTMLButtonElement>("[role=tab]") : null;
    if (button?.parentElement === ctx.root && !button.disabled) select(button);
  });
  ctx.on<KeyboardEvent>(ctx.root, "keydown", event => {
    const buttons = tabs().filter(button => !button.disabled);
    const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
    if (index < 0 || !["ArrowRight", "ArrowLeft", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const button = buttons[event.key === "Home" ? 0 : event.key === "End" ? buttons.length - 1
      : (index + (event.key === "ArrowRight" ? 1 : buttons.length - 1)) % buttons.length];
    if (button) {
      select(button);
      button.focus();
      button.scrollIntoView({ block: "nearest", inline: "nearest" });
    }
  });
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => {
    if (event.detail.target.contains(ctx.root)) sync();
  });
  ctx.onUpdate(sync);
  sync();
}
