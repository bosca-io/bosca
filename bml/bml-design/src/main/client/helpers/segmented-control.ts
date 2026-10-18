import type { ClientContext, IslandUpdate } from "@bosca/bml";

/** Maintains one form value and roving keyboard focus for this radio group. */
export function segmentedControl(ctx: ClientContext) {
  const options = () => [...ctx.root.querySelectorAll<HTMLButtonElement>(":scope > [role=radio]")];
  const input = ctx.ref<HTMLInputElement>("value");
  let serverValue: string | undefined;
  const mark = (value: string) => {
    const buttons = options();
    const selected = buttons.find(button => button.dataset.value === value);
    for (const button of buttons) {
      button.setAttribute("aria-checked", String(button === selected));
      button.tabIndex = button === (selected ?? buttons[0]) ? 0 : -1;
    }
    if (input) input.value = value;
  };
  const sync = () => {
    const next = ctx.root.dataset.value ?? "";
    if (serverValue !== next) mark(next);
    serverValue = next;
    if (input) input.name = ctx.root.dataset.name ?? "";
  };
  const choose = (button: HTMLButtonElement) => {
    mark(button.dataset.value ?? "");
    ctx.root.dispatchEvent(new CustomEvent("segmentchange", { bubbles: true }));
  };
  ctx.on<MouseEvent>(ctx.root, "click", event => {
    const button = event.target instanceof Element ? event.target.closest<HTMLButtonElement>("[role=radio]") : null;
    if (button?.parentElement === ctx.root) choose(button);
  });
  ctx.on<KeyboardEvent>(ctx.root, "keydown", event => {
    const buttons = options();
    const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
    if (index < 0 || !["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const button = buttons[event.key === "Home" ? 0 : event.key === "End" ? buttons.length - 1
      : (index + (event.key === "ArrowRight" ? 1 : buttons.length - 1)) % buttons.length];
    if (button) { choose(button); button.focus(); }
  });
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => {
    if (event.detail.target.contains(ctx.root)) sync();
  });
  ctx.onUpdate(sync);
  sync();
}
