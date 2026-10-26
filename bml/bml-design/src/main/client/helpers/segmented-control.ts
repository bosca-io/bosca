import type { ClientContext, IslandUpdate } from "@bosca/bml";

/** Maintains one form value and roving keyboard focus for this radio group. */
export function segmentedControl(ctx: ClientContext) {
  const options = () => [...ctx.root.querySelectorAll<HTMLButtonElement>(":scope > [role=radio]")];
  const input = ctx.ref<HTMLInputElement>("value");
  let serverValue: string | undefined;
  const mark = (value: string) => {
    const buttons = options();
    const selected = buttons.find(button => button.dataset.value === value);
    const focusable = selected && !selected.disabled ? selected : buttons.find(button => !button.disabled);
    for (const button of buttons) {
      button.setAttribute("aria-checked", String(button === selected));
      button.tabIndex = button === focusable ? 0 : -1;
    }
    if (input) input.value = value;
  };
  const sync = () => {
    const metadata = new Map((JSON.parse(ctx.root.dataset.options ?? "[]") as { value: string; label: string; disabled?: boolean }[])
      .map(option => [option.value, option]));
    for (const button of options()) {
      const option = metadata.get(button.dataset.value ?? "");
      if (!option) continue;
      button.disabled = Boolean(option.disabled);
      const label = button.querySelector<HTMLElement>("[data-option-label]");
      if (label) label.textContent = option.label;
    }
    const next = ctx.root.dataset.value ?? "";
    if (serverValue !== next) mark(next);
    else mark(input?.value ?? next);
    serverValue = next;
    if (input) input.name = ctx.root.dataset.name ?? "";
  };
  const choose = (button: HTMLButtonElement) => {
    mark(button.dataset.value ?? "");
    ctx.root.dispatchEvent(new CustomEvent("segmentchange", { bubbles: true }));
  };
  ctx.on<MouseEvent>(ctx.root, "click", event => {
    const button = event.target instanceof Element ? event.target.closest<HTMLButtonElement>("[role=radio]") : null;
    if (button?.parentElement === ctx.root && !button.disabled) choose(button);
  });
  ctx.on<KeyboardEvent>(ctx.root, "keydown", event => {
    const buttons = options().filter(button => !button.disabled);
    const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
    if (index < 0 || !["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const button = buttons[event.key === "Home" ? 0 : event.key === "End" ? buttons.length - 1
      : (index + (event.key === "ArrowRight" ? 1 : buttons.length - 1)) % buttons.length];
    if (button) {
      choose(button);
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
