import type { ClientContext } from "@bosca/bml";

/** Retains WorkOps's existing browser preference, with explicit URL state taking precedence. */
export function workOpsBqlQuery(ctx: ClientContext, lookup: {
  validate(source: string): Promise<Record<string, string>[]>;
  validateSpec(source: string): Promise<Record<string, string>[]>;
}) {
  const form = ctx.root instanceof HTMLFormElement ? ctx.root : ctx.root.querySelector<HTMLFormElement>("form");
  const field = form?.querySelector<HTMLTextAreaElement>('textarea[name="bql"]');
  if (!form || !field) return;
  const specification = form.dataset.bqlKind === "spec";
  const storageKey = specification ? "workops-specs-bql" : "workops-tasks-bql";
  const remember = (source: string | null) => {
    try {
      if (source === null || source === "") localStorage.removeItem(storageKey);
      else localStorage.setItem(storageKey, source);
    } catch { /* Search remains usable when browser storage is unavailable. */ }
  };
  const url = new URL(location.href);
  if (url.searchParams.has("bql")) remember(url.searchParams.get("bql"));
  else {
    let stored: string | null = null;
    try { stored = localStorage.getItem(storageKey); } catch { /* Browser preferences are optional. */ }
    if (stored && stored !== field.value) {
      url.searchParams.set("bql", stored);
      url.searchParams.delete("offset");
      location.replace(url);
      return;
    }
  }
  const validation = ctx.ref<HTMLElement>("validation"), validationText = ctx.ref<HTMLElement>("validationText");
  let revision = 0, disposed = false, timer: ReturnType<typeof setTimeout> | undefined;
  let validatedSource = field.value, blocked = Boolean(form.dataset.validationMessage);
  if (validation) validation.id = ctx.scoped("validation");
  const report = (message: string, invalid: boolean) => {
    blocked = invalid;
    if (validation && validationText) { validationText.textContent = message; validation.hidden = !message; }
    for (const control of [field, form.querySelector<HTMLElement>('[role="textbox"]')]) {
      control?.setAttribute("aria-invalid", String(invalid));
      if (invalid && validation) control?.setAttribute("aria-errormessage", validation.id); else control?.removeAttribute("aria-errormessage");
    }
  };
  ctx.on(field, "input", () => {
    const current = ++revision, source = field.value;
    if (timer !== undefined) clearTimeout(timer);
    report("", false);
    timer = setTimeout(async () => {
      try {
        const diagnostics = await (specification ? lookup.validateSpec(source) : lookup.validate(source));
        if (disposed || current !== revision || field.value !== source) return;
        validatedSource = source; report(diagnostics.map(row => row.description).join("\n"), diagnostics.length > 0);
      } catch (error) {
        if (disposed || current !== revision || field.value !== source) return;
        report(`Could not validate the query. ${error instanceof Error ? error.message : "Try Search to validate it again."}`, false);
      }
    }, 400);
  });
  ctx.on<SubmitEvent>(form, "submit", event => {
    if (blocked && validatedSource === field.value) {
      event.preventDefault(); (form.querySelector<HTMLElement>('[role="textbox"]') ?? field).focus(); return;
    }
    remember(field.value);
  });
  ctx.on<KeyboardEvent>(document, "keydown", event => {
    if (event.defaultPrevented || event.key !== "/" || event.ctrlKey || event.metaKey || event.altKey || event.isComposing || !form.isConnected) return;
    const target = event.target;
    if (target instanceof HTMLElement && target.isContentEditable || target instanceof Element && target.closest('input, textarea, select, dialog[open], [role="textbox"], [role="dialog"], [role="combobox"], [role="listbox"], [role="menu"]')) return;
    event.preventDefault();
    (form.querySelector<HTMLElement>('[role="textbox"]') ?? field).focus();
  });
  ctx.on<KeyboardEvent>(form, "keydown", event => {
    if (event.key !== "Enter" || (!event.ctrlKey && !event.metaKey) || event.isComposing) return;
    event.preventDefault();
    form.requestSubmit();
  }, true);
  const clear = form.querySelector<HTMLElement>("[data-workops-clear-query]");
  if (clear) ctx.on(clear, "click", () => {
    remember(null);
    const next = new URL(location.href);
    next.searchParams.set("bql", "");
    next.searchParams.delete("offset");
    location.assign(next);
  });
  ctx.onUnmount(() => { disposed = true; revision++; if (timer !== undefined) clearTimeout(timer); });
  report(form.dataset.validationMessage ?? "", blocked);
}
