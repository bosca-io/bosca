import type { ClientContext, IslandUpdate } from "@bosca/bml";
import type { SelectOption, SelectPage, SelectSearch } from "@bosca/bml-design/select";

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
  const pagination = ctx.ref<HTMLElement>("pagination");
  const previous = ctx.ref<HTMLButtonElement>("previous");
  const next = ctx.ref<HTMLButtonElement>("next");
  let previousOffset: number | undefined;
  let nextOffset: number | undefined;
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
  const number = (key: string, fallback: number) => {
    const value = Number(ctx.root.dataset[key]);
    return ctx.root.dataset[key] !== undefined && Number.isFinite(value) ? Math.max(0, value) : fallback;
  };
  let searchable = ctx.root.hasAttribute("data-searchable");
  let asynchronous = ctx.root.hasAttribute("data-async");
  let allowCustom = ctx.root.hasAttribute("data-allow-custom");
  let placeholder = ctx.root.dataset.placeholder || "Select…";
  let maxTags = Math.max(1, number("maxTags", 3));
  let minQuery = number("minQuery", 0);
  let debounce = number("debounce", 250);
  let disposed = false;
  let focused: HTMLElement | null = null;
  const initial: SelectOption[] = [...native.options].map((option) => ({
    value: option.value,
    label: option.label,
    disabled: option.disabled || option.parentElement instanceof HTMLOptGroupElement && option.parentElement.disabled,
  }));
  const remembered = new Map<string, SelectOption>(
    initial.map((option) => [option.value, option]),
  );
  const persistent = initial.filter((_, index) => native.options[index].hasAttribute("data-persistent"));
  const persistentValues = new Set(persistent.map(option => option.value));
  const withPersistent = (options: SelectOption[]) => [...persistent, ...options.filter(option => !persistentValues.has(option.value))];
  let choices: SelectOption[] = initial;
  let active = -1;
  let revision = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let expanded = false;
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
    if (disposed || !expanded || !ctx.root.isConnected) return;
    const bounds = control.getBoundingClientRect();
    const width = document.documentElement.clientWidth;
    popup.style.width = `${Math.max(0, Math.min(Math.max(bounds.width, 220), width - 24))}px`;
    popup.style.maxHeight = `${Math.max(0, Math.min(320, innerHeight - 24))}px`;
    const height = popup.getBoundingClientRect().height;
    const below = innerHeight - bounds.bottom - 12;
    const top = below >= height + 4 ? bounds.bottom + 4 : bounds.top - height - 4;
    popup.style.left = `${Math.max(12, Math.min(bounds.left, width - popup.getBoundingClientRect().width - 12))}px`;
    popup.style.top = `${Math.max(12, Math.min(top, innerHeight - height - 12))}px`;
    popup.style.bottom = "auto";
  };
  const close = (restore = false) => {
    expanded = false;
    revision++;
    if (timer !== undefined) clearTimeout(timer);
    timer = undefined;
    if (popup.matches(":popover-open")) popup.hidePopover();
    popup.hidden = true;
    popup.removeAttribute("aria-busy");
    trigger.setAttribute("aria-expanded", "false");
    query.setAttribute("aria-expanded", "false");
    query.removeAttribute("aria-activedescendant");
    trigger.removeAttribute("aria-activedescendant");
    focused = null;
    if (restore && trigger.isConnected && !trigger.disabled) trigger.focus({ preventScroll: true });
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
    // An unset native value keeps the placeholder presentation.
    ctx.root.toggleAttribute("data-empty", !values.some((value) => value !== ""));
    trigger.disabled = native.disabled || ctx.root.hasAttribute("data-loading");
    trigger.setAttribute("aria-busy", String(ctx.root.hasAttribute("data-loading")));
    trigger.setAttribute("aria-required", String(native.required));
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
        remove.disabled = trigger.disabled;
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
    if (!option || option.disabled || (option.custom && !allowCustom) || native.disabled || trigger.disabled || disposed) return;
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
      if (searchable) query.focus({ preventScroll: true });
      else trigger.focus({ preventScroll: true });
    } else close(true);
  };
  const filter = (offset = 0) => {
    const value = query.value.trim();
    const current = ++revision;
    if (timer !== undefined) clearTimeout(timer);
    timer = undefined;
    if (pagination) pagination.hidden = true;
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
      render(withPersistent(options));
      return;
    }
    if (value.length < minQuery) {
      popup.removeAttribute("aria-busy");
      status.textContent = minQuery
        ? `Enter at least ${minQuery} characters to search.`
        : "";
      render(persistent);
      return;
    }
    popup.setAttribute("aria-busy", "true");
    status.textContent = "Searching…";
    render(persistent);
    timer = setTimeout(async () => {
      timer = undefined;
      try {
        const result = await new Promise<SelectOption[] | SelectPage>((resolve, reject) => {
          ctx.root.dispatchEvent(
            new CustomEvent<SelectSearch>("selectsearch", {
              bubbles: true,
              detail: { query: value, offset, resolve, reject },
            }),
          );
        });
        if (disposed || current !== revision || !expanded) return;
        let options = Array.isArray(result) ? result : result.options;
        previousOffset = Array.isArray(result) ? undefined : result.previousOffset;
        nextOffset = Array.isArray(result) ? undefined : result.nextOffset;
        if (pagination) pagination.hidden = previousOffset === undefined && nextOffset === undefined;
        if (previous) previous.disabled = previousOffset === undefined;
        if (next) next.disabled = nextOffset === undefined;
        if (allowCustom && value && !options.some((option) => option.value === value)) {
          options = [...options, { value, label: `Use “${value}”`, custom: true }];
        }
        popup.removeAttribute("aria-busy");
        const offered = withPersistent(options);
        offered.forEach((option) => remembered.set(option.value, option));
        status.textContent = options.length ? "" : "No options found.";
        render(offered);
      } catch {
        if (disposed || current !== revision || !expanded) return;
        popup.removeAttribute("aria-busy");
        status.textContent = "Search failed. Please try again.";
        render(persistent);
      }
    }, debounce);
  };
  const open = () => {
    if (disposed || native.disabled || trigger.disabled) return;
    expanded = true;
    popup.hidden = false;
    popup.showPopover();
    trigger.setAttribute("aria-expanded", "true");
    query.setAttribute("aria-expanded", "true");
    query.value = "";
    status.textContent = "";
    filter();
    position();
    if (searchable) query.focus({ preventScroll: true });
  };
  ctx.on(trigger, "click", () => (expanded ? close() : open()));
  ctx.on(query, "input", () => filter());
  ctx.on<KeyboardEvent>(popup, "keydown", (event) => {
    if (event.key === "Escape" && expanded) {
      event.preventDefault();
      event.stopPropagation();
      close(true);
    }
  });
  if (previous) ctx.on(previous, "click", () => {
    if (previousOffset !== undefined) { filter(previousOffset); (searchable ? query : trigger).focus(); }
  });
  if (next) ctx.on(next, "click", () => {
    if (nextOffset !== undefined) { filter(nextOffset); (searchable ? query : trigger).focus(); }
  });
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
    if (!remove || native.disabled || trigger.disabled) return;
    const option = [...native.options].find(
      (entry) => entry.value === remove.dataset.remove,
    );
    if (option) option.selected = false;
    notify();
    trigger.focus();
  });
  const keydown = (event: KeyboardEvent) => {
    if (native.disabled || trigger.disabled) return;
    if (event.key === "Escape" && expanded) {
      event.preventDefault();
      event.stopPropagation();
      close(true);
      return;
    }
    if (event.key === "Tab") {
      if (expanded && pagination && !pagination.hidden && event.target === query && !event.shiftKey) return;
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
      else if (active >= 0) choose(active);
    } else if (event.key === "Backspace" && native.multiple && !query.value) {
      const last = [...native.selectedOptions].at(-1);
      if (last) {
        event.preventDefault();
        last.selected = false;
        notify();
        if (expanded) render(choices);
      }
    }
  };
  ctx.on<KeyboardEvent>(trigger, "keydown", keydown);
  ctx.on<KeyboardEvent>(query, "keydown", keydown);
  ctx.on(native, "change", () => {
    renderLabel();
    if (expanded) render(choices);
  });
  ctx.on<FocusEvent>(popup, "focusin", event => {
    if (event.target instanceof HTMLElement) focused = event.target;
  });
  ctx.on<FocusEvent>(popup, "focusout", event => {
    // The focus destination is available before activeElement settles during a native Tab move.
    if (event.relatedTarget === trigger) {
      focused = null;
      return;
    }
    if (event.relatedTarget instanceof Node && popup.contains(event.relatedTarget)) return;
    queueMicrotask(() => {
      if (!expanded || disposed || popup.contains(document.activeElement)) return;
      focused = null;
      if (document.activeElement !== trigger) close();
    });
  });
  ctx.on(native, "invalid", (event) => {
    event.preventDefault();
    trigger.setAttribute("aria-invalid", "true");
    trigger.focus();
  });
  ctx.on<PointerEvent>(document, "pointerdown", (event) => {
    if (!event.composedPath().includes(ctx.root)) close();
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

  const measure = () => {
    if (disposed || !ctx.root.isConnected) return;
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
  const sync = () => {
    if (disposed || !ctx.root.isConnected) return;
    native.disabled = ctx.root.hasAttribute("data-disabled");
    native.required = ctx.root.hasAttribute("data-required");
    native.name = ctx.root.dataset.name ?? "";
    const fieldLabel = native.closest("label")?.querySelector(":scope > span")?.textContent?.trim();
    const name = fieldLabel || ctx.root.dataset.label || "Selection";
    native.setAttribute("aria-label", ctx.root.dataset.label || "Selection");
    trigger.setAttribute("aria-label", name);
    query.setAttribute("aria-label", `Search ${name.toLowerCase()}`);
    list.setAttribute("aria-label", name);
    for (const attribute of ["aria-describedby", "aria-labelledby"]) {
      const value = native.getAttribute(attribute);
      if (value) trigger.setAttribute(attribute, value);
      else trigger.removeAttribute(attribute);
    }
    query.setAttribute("aria-describedby", `${status.id} ${native.getAttribute("aria-describedby") ?? ""}`.trim());
    placeholder = ctx.root.dataset.placeholder || "Select…";
    searchable = ctx.root.hasAttribute("data-searchable");
    asynchronous = ctx.root.hasAttribute("data-async");
    const nextAllowCustom = ctx.root.hasAttribute("data-allow-custom");
    const customChanged = allowCustom !== nextAllowCustom;
    allowCustom = nextAllowCustom;
    maxTags = Math.max(1, number("maxTags", 3));
    minQuery = number("minQuery", 0);
    debounce = number("debounce", 250);
    query.hidden = !searchable;
    renderLabel();
    measure();
    if (expanded && trigger.disabled) close();
    if (expanded) {
      if (customChanged) {
        if (!asynchronous) filter();
        else {
          const options = choices.filter(option => !option.custom);
          const value = query.value.trim();
          const busy = popup.hasAttribute("aria-busy");
          if (allowCustom && !busy && value.length >= minQuery && value && !options.some(option => option.value === value)) {
            options.push({ value, label: `Use “${value}”`, custom: true });
          }
          if (!busy && value.length >= minQuery && (!status.textContent || status.textContent === "No options found.")) {
            status.textContent = options.length ? "" : "No options found.";
          }
          render(options);
        }
      }
      if (!popup.matches(":popover-open")) popup.showPopover();
      position();
      if (focused?.isConnected) focused.focus({ preventScroll: true });
    }
  };
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => {
    if (event.detail.target.contains(ctx.root)) sync();
  });
  ctx.onUpdate(sync);
  ctx.on(document, "reset", event => {
    if (event.target !== native.form) return;
    queueMicrotask(() => {
      if (disposed) return;
      close();
      trigger.removeAttribute("aria-invalid");
      renderLabel();
    });
  }, true);
  const observer = new ResizeObserver(() => { if (expanded) position(); });
  observer.observe(control);
  observer.observe(popup);
  ctx.onUnmount(() => {
    disposed = true;
    close();
    observer.disconnect();
  });
  sync();
  if (nativeHadFocus && !trigger.disabled) trigger.focus();
  void document.fonts.ready.then(() => { if (!disposed) { measure(); position(); } });
}
