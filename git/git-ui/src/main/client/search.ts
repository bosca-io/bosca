import type { IslandContext } from "@bosca/bml";

/** Debounces the declarative search form while preserving the query and suppressing stale results. */
export function repositorySearch(ctx: IslandContext) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let changingRef = false;
  const form = () => ctx.root.querySelector<HTMLFormElement>("form");
  const criteria = () => {
    const current = form();
    const data = current ? new FormData(current) : new FormData();
    return {
      repo: String(data.get("repo") ?? ""),
      query: String(data.get("q") ?? ""),
      mode: String(data.get("mode") ?? "content"),
      ref: String(data.get("ref") ?? "").replace(/^refs\/heads\//, ""),
    };
  };
  let desired = criteria();
  const loading = (value: boolean) => {
    const indicator = ctx.ref<HTMLElement>("loading");
    if (indicator) indicator.hidden = !value;
    const results = ctx.root.querySelector<HTMLElement>(".search-results");
    if (results) {
      results.hidden = value;
      results.setAttribute("aria-busy", String(value));
    }
  };
  const submit = () => form()?.requestSubmit();
  const setDefaultRef = () => {
    const current = form();
    const repository = current?.elements.namedItem(
      "repo",
    ) as HTMLSelectElement | null;
    const ref = current?.elements.namedItem("ref") as HTMLSelectElement | null;
    const value = repository?.selectedOptions[0]?.dataset.defaultRef;
    if (!ref || !value) return;
    if (![...ref.options].some((option) => option.value === value))
      ref.add(new Option(value, value));
    ref.value = value;
    changingRef = true;
    ref.dispatchEvent(new Event("change", { bubbles: true }));
    changingRef = false;
  };
  ctx.on<InputEvent>(ctx.root, "input", (event) => {
    if (
      !(event.target instanceof HTMLInputElement) ||
      event.target.name !== "q" ||
      event.isComposing
    )
      return;
    if (timer) clearTimeout(timer);
    desired = criteria();
    loading(true);
    timer = setTimeout(submit, event.target.value.trim() ? 300 : 0);
  });
  ctx.on<Event>(ctx.root, "compositionend", (event) => {
    if (event.target instanceof HTMLInputElement && event.target.name === "q")
      event.target.dispatchEvent(new Event("input", { bubbles: true }));
  });
  ctx.on<Event>(ctx.root, "change", (event) => {
    if (changingRef || !(event.target instanceof HTMLSelectElement)) return;
    if (event.target.name === "repo") setDefaultRef();
    submit();
  });
  ctx.on(ctx.root, "segmentchange", () => {
    const values = criteria();
    if (values.mode === "content") {
      const ref = form()?.elements.namedItem("ref") as HTMLSelectElement | null;
      if (ref?.selectedOptions[0]?.dataset.kind !== "branch") setDefaultRef();
    }
    submit();
  });
  ctx.on<SubmitEvent>(
    ctx.root,
    "submit",
    async (event) => {
      // Enhance the declarative form with coalescing and transport feedback.
      event.preventDefault();
      event.stopPropagation();
      if (timer) clearTimeout(timer);
      const request = criteria();
      desired = request;
      const error = ctx.ref<HTMLElement>("transportError");
      if (error) error.hidden = true;
      loading(true);
      try {
        await ctx.dispatch(
          "model",
          "search",
          [request.repo, request.query, request.mode, request.ref],
          { coalesce: true },
        );
      } catch {
        if (JSON.stringify(request) !== JSON.stringify(desired)) return;
        const indicator = ctx.ref<HTMLElement>("loading");
        if (indicator) indicator.hidden = true;
        const results = ctx.root.querySelector<HTMLElement>(".search-results");
        if (results) results.hidden = true;
        if (error) error.hidden = false;
      }
    },
    true,
  );
  ctx.onUpdate(() => {
    const state = ctx.root.querySelector<HTMLElement>("[data-search-query]");
    if (!state) return;
    if (
      state.dataset.searchQuery !== desired.query ||
      state.dataset.searchRepo !== desired.repo ||
      state.dataset.searchMode !== desired.mode ||
      state.dataset.searchRef !== desired.ref
    ) {
      loading(true);
      return;
    }
    loading(false);
    const error = ctx.ref<HTMLElement>("transportError");
    if (error) error.hidden = true;
    if (state.dataset.searchHref)
      history.replaceState(null, "", state.dataset.searchHref);
  });
  ctx.onUnmount(() => {
    if (timer) clearTimeout(timer);
  });
}
