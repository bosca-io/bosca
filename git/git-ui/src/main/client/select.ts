import type { ClientContext } from "@bosca/bml";

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
  custom?: boolean;
}
export interface SelectSearch {
  query: string;
  resolve: (options: SelectOption[]) => void;
  reject: (error: unknown) => void;
}

/** Enhances a native form select; the native values remain the form submission contract. */
export function richSelect(ctx: ClientContext) {
  const native = ctx.root.querySelector<HTMLSelectElement>("select");
  const control = ctx.ref<HTMLElement>("control");
  const trigger = ctx.ref<HTMLButtonElement>("trigger");
  const label = ctx.ref<HTMLElement>("selection");
  const tags = ctx.ref<HTMLElement>("tags");
  const query = ctx.ref<HTMLInputElement>("query");
  const popup = ctx.ref<HTMLElement>("popup");
  const list = ctx.ref<HTMLElement>("options");
  const status = ctx.ref<HTMLElement>("status");
  if (
    !native ||
    !control ||
    !trigger ||
    !label ||
    !tags ||
    !query ||
    !popup ||
    !list ||
    !status
  )
    return;
  const fieldLabel = native
    .closest("label")
    ?.querySelector(":scope > span")
    ?.textContent?.trim();
  if (fieldLabel) {
    trigger.setAttribute("aria-label", fieldLabel);
    query.setAttribute("aria-label", `Search ${fieldLabel.toLowerCase()}`);
  }
  const searchable = ctx.root.hasAttribute("data-searchable");
  const asynchronous = ctx.root.hasAttribute("data-async");
  const allowCustom = ctx.root.hasAttribute("data-allow-custom");
  const placeholder = ctx.root.dataset.placeholder || "Select…";
  const maxTags = Math.max(1, Number(ctx.root.dataset.maxTags) || 3);
  const minQuery = Math.max(0, Number(ctx.root.dataset.minQuery) || 0);
  const debounce = Math.max(0, Number(ctx.root.dataset.debounce) || 250);
  const initial: SelectOption[] = [...native.options].map((option) => ({
    value: option.value,
    label: option.label,
    disabled: option.disabled,
  }));
  const remembered = new Map<string, SelectOption>(
    initial.map((option) => [option.value, option]),
  );
  let choices: SelectOption[] = initial;
  let active = -1;
  let revision = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let expanded = false;
  let searching = false;
  const id = `select-${crypto.randomUUID()}`;
  list.id = `${id}-options`;
  status.id = `${id}-status`;
  trigger.setAttribute("aria-controls", list.id);
  query.setAttribute("aria-controls", list.id);
  query.setAttribute("aria-describedby", status.id);
  list.setAttribute("aria-multiselectable", String(native.multiple));
  const nativeHadFocus = document.activeElement === native;
  native.classList.add("enhanced");
  native.tabIndex = -1;
  native.setAttribute("aria-hidden", "true");
  control.hidden = false;
  query.hidden = !searchable;
  query.placeholder = "Search…";
  const selected = () =>
    [...native.selectedOptions].map((option) => option.value);
  const position = () => {
    const bounds = control.getBoundingClientRect();
    const available = innerWidth - 24;
    popup.style.width = `${Math.min(Math.max(bounds.width, 220), available)}px`;
    popup.style.left = `${Math.max(12, Math.min(bounds.left, innerWidth - popup.getBoundingClientRect().width - 12))}px`;
    const below = innerHeight - bounds.bottom - 12;
    const above = bounds.top - 12;
    const upward = below < 260 && above > below;
    popup.style.maxHeight = `${Math.max(80, Math.min(320, upward ? above : below))}px`;
    popup.style.top = upward ? "auto" : `${bounds.bottom + 4}px`;
    popup.style.bottom = upward ? `${innerHeight - bounds.top + 4}px` : "auto";
  };
  const close = (restore = false) => {
    expanded = false;
    revision++;
    if (timer) clearTimeout(timer);
    if (popup.matches(":popover-open")) popup.hidePopover();
    popup.hidden = true;
    searching = false;
    popup.removeAttribute("aria-busy");
    trigger.setAttribute("aria-expanded", "false");
    query.setAttribute("aria-expanded", "false");
    query.removeAttribute("aria-activedescendant");
    trigger.removeAttribute("aria-activedescendant");
    if (restore) trigger.focus();
  };
  const mark = () => {
    [...list.children].forEach((item, index) =>
      item.toggleAttribute("data-highlighted", index === active),
    );
    const target = searchable ? query : trigger;
    if (active >= 0) {
      target.setAttribute("aria-activedescendant", `${id}-${active}`);
      list.children[active]?.scrollIntoView({ block: "nearest" });
    } else target.removeAttribute("aria-activedescendant");
  };
  const renderLabel = () => {
    const values = selected();
    const labels = values.map((value) => remembered.get(value)?.label ?? value);
    label.textContent = native.multiple
      ? values.length
        ? `${values.length} selected`
        : placeholder
      : labels[0] || placeholder;
    // Studio shows an unset choice in its muted placeholder colour.
    ctx.root.toggleAttribute("data-empty", !values.some((value) => value !== ""));
    trigger.disabled = native.disabled || ctx.root.hasAttribute("data-loading");
    tags.replaceChildren();
    tags.hidden = !native.multiple || !values.length;
    if (native.multiple) {
      values.slice(0, maxTags).forEach((value) => {
        const tag = document.createElement("span");
        tag.className = "select-tag";
        const text = document.createElement("span");
        text.textContent = remembered.get(value)?.label ?? value;
        const remove = document.createElement("button");
        remove.type = "button";
        remove.textContent = "×";
        remove.dataset.remove = value;
        remove.disabled = native.disabled;
        remove.setAttribute("aria-label", `Remove ${text.textContent}`);
        tag.append(text, remove);
        tags.append(tag);
      });
      if (values.length > maxTags) {
        const extra = document.createElement("span");
        extra.textContent = `+${values.length - maxTags}`;
        tags.append(extra);
      }
    }
  };
  const render = (options: SelectOption[]) => {
    choices = options;
    const values = new Set(selected());
    list.replaceChildren(
      ...options.map((option, index) => {
        const item = document.createElement("li");
        item.id = `${id}-${index}`;
        item.setAttribute("role", "option");
        item.setAttribute("aria-selected", String(values.has(option.value)));
        item.setAttribute("aria-disabled", String(option.disabled === true));
        item.dataset.index = String(index);
        item.textContent = option.label;
        return item;
      }),
    );
    active = options.findIndex(
      (option) => !option.disabled && values.has(option.value),
    );
    if (active < 0) active = options.findIndex((option) => !option.disabled);
    mark();
    position();
  };
  const notify = () => {
    trigger.removeAttribute("aria-invalid");
    renderLabel();
    native.dispatchEvent(new Event("change", { bubbles: true }));
  };
  const choose = (index: number) => {
    const option = choices[index];
    if (!option || option.disabled || native.disabled) return;
    remembered.set(
      option.value,
      option.custom ? { ...option, label: option.value } : option,
    );
    let element = [...native.options].find(
      (entry) => entry.value === option.value,
    );
    if (!element) {
      element = new Option(
        option.custom ? option.value : option.label,
        option.value,
      );
      native.add(element);
    }
    if (native.multiple) element.selected = !element.selected;
    else native.value = option.value;
    notify();
    if (native.multiple) {
      render(choices);
      query.focus();
    } else close(true);
  };
  const filter = () => {
    const value = query.value.trim();
    const current = ++revision;
    if (timer) clearTimeout(timer);
    if (!asynchronous) {
      let options: SelectOption[] = initial.filter(
        (option) =>
          option.label.toLowerCase().includes(value.toLowerCase()) ||
          option.value.toLowerCase().includes(value.toLowerCase()),
      );
      if (
        allowCustom &&
        value &&
        !initial.some(
          (option) => option.value.toLowerCase() === value.toLowerCase(),
        )
      )
        options = [
          ...options,
          { value, label: `Use “${value}”`, custom: true },
        ];
      status.textContent = options.length ? "" : "No options found.";
      render(options);
      return;
    }
    if (value.length < minQuery) {
      searching = false;
      popup.removeAttribute("aria-busy");
      status.textContent = minQuery
        ? `Enter at least ${minQuery} characters to search.`
        : "";
      render(initial.filter((option) => option.value === "all"));
      return;
    }
    searching = true;
    popup.setAttribute("aria-busy", "true");
    status.textContent = "Searching…";
    render([]);
    timer = setTimeout(async () => {
      try {
        const options = await new Promise<SelectOption[]>((resolve, reject) => {
          ctx.root.dispatchEvent(
            new CustomEvent<SelectSearch>("selectsearch", {
              bubbles: true,
              detail: { query: value, resolve, reject },
            }),
          );
        });
        if (current !== revision || !expanded) return;
        searching = false;
        popup.removeAttribute("aria-busy");
        options.forEach((option) => remembered.set(option.value, option));
        status.textContent = options.length ? "" : "No options found.";
        render(options);
      } catch {
        if (current !== revision || !expanded) return;
        searching = false;
        popup.removeAttribute("aria-busy");
        status.textContent = "Search failed. Please try again.";
        render([]);
      }
    }, debounce);
  };
  const open = () => {
    if (native.disabled || trigger.disabled) return;
    expanded = true;
    popup.hidden = false;
    popup.showPopover();
    trigger.setAttribute("aria-expanded", "true");
    query.setAttribute("aria-expanded", "true");
    query.value = "";
    status.textContent = "";
    filter();
    position();
    if (searchable) query.focus();
  };
  ctx.on(trigger, "click", () => (expanded ? close() : open()));
  ctx.on(query, "input", filter);
  ctx.on<PointerEvent>(list, "pointerdown", (event) => {
    if (!(event.target instanceof Element)) return;
    const item = event.target.closest<HTMLElement>("[data-index]");
    if (!item) return;
    event.preventDefault();
    choose(Number(item.dataset.index));
  });
  ctx.on<MouseEvent>(tags, "click", (event) => {
    if (!(event.target instanceof Element)) return;
    const remove = event.target.closest<HTMLButtonElement>("[data-remove]");
    if (!remove || native.disabled) return;
    const option = [...native.options].find(
      (entry) => entry.value === remove.dataset.remove,
    );
    if (option) option.selected = false;
    notify();
    trigger.focus();
  });
  const keydown = (event: KeyboardEvent) => {
    if (event.key === "Escape" && expanded) {
      event.preventDefault();
      event.stopPropagation();
      close(true);
      return;
    }
    if (event.key === "Tab") {
      close();
      return;
    }
    if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
      event.preventDefault();
      if (!expanded) open();
      const enabled = choices
        .map((option, index) => (option.disabled ? -1 : index))
        .filter((index) => index >= 0);
      if (!enabled.length) return;
      const index = enabled.indexOf(active);
      active =
        event.key === "Home"
          ? enabled[0]
          : event.key === "End"
            ? enabled[enabled.length - 1]
            : enabled[
                (index +
                  (event.key === "ArrowDown" ? 1 : -1) +
                  enabled.length) %
                  enabled.length
              ];
      mark();
    } else if (event.key === "Enter" || (!searchable && event.key === " ")) {
      event.preventDefault();
      if (!expanded) open();
      else if (!searching && active >= 0) choose(active);
    } else if (event.key === "Backspace" && native.multiple && !query.value) {
      const last = [...native.selectedOptions].at(-1);
      if (last) {
        last.selected = false;
        notify();
        if (expanded) render(choices);
      }
    }
  };
  ctx.on<KeyboardEvent>(trigger, "keydown", keydown);
  ctx.on<KeyboardEvent>(query, "keydown", keydown);
  ctx.on(native, "change", renderLabel);
  ctx.on(native, "invalid", (event) => {
    event.preventDefault();
    trigger.setAttribute("aria-invalid", "true");
    trigger.focus();
  });
  ctx.on<PointerEvent>(document, "pointerdown", (event) => {
    if (event.target instanceof Node && !ctx.root.contains(event.target))
      close();
  });
  ctx.on(window, "resize", () => {
    if (expanded) position();
  });
  ctx.on(
    window,
    "scroll",
    () => {
      if (expanded) position();
    },
    true,
  );
  ctx.onUnmount(() => close());
  const measure = () => {
    if (!ctx.root.isConnected) return;
    const canvas = document.createElement("canvas");
    const drawing = canvas.getContext("2d");
    if (!drawing) return;
    const style = getComputedStyle(trigger);
    drawing.font = style.font;
    const widest = Math.max(
      ...initial.map((option) => drawing.measureText(option.label).width),
      drawing.measureText(placeholder).width,
    );
    ctx.root.style.minWidth = `min(${Math.ceil(widest + 50)}px, 100%)`;
  };
  renderLabel();
  if (nativeHadFocus && !native.disabled) trigger.focus();
  measure();
  void document.fonts.ready.then(measure);
}
