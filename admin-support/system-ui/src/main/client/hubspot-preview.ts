import type { ClientContext, IslandUpdate } from "@bosca/bml";
import jsonata from "jsonata";

/** Preview evaluates local drafts; profile search/context and synchronization use server actions. */
export function mountHubSpotPreview(ctx: ClientContext) {
  let revision = 0, disposed = false;
  const preview = async () => {
    const current = ++revision;
    const state = ctx.root.querySelector<HTMLElement>("[data-hubspot-preview]");
    const output = state?.querySelector<HTMLTextAreaElement>('textarea[name="hubspotOutput"]');
    const error = state?.querySelector<HTMLElement>("[data-hubspot-preview-error]");
    if (!state || !output || !error) return;
    const publish = (text: string, failure = "") => {
      if (disposed || current !== revision) return;
      output.value = text; output.dispatchEvent(new Event("input", { bubbles: true }));
      error.textContent = failure; error.hidden = !failure;
    };
    const name = state.dataset.profileType === "ORGANIZATION" ? "organizationExpression" : "genericExpression";
    const expression = ctx.root.querySelector<HTMLTextAreaElement>(`[data-hubspot-settings] textarea[name="${name}"]`)?.value ?? "";
    if (!state.dataset.context || state.dataset.context === "null") { publish("", "No profile context loaded."); return; }
    if (!expression.trim()) { publish("", `No ${name === "organizationExpression" ? "organization" : "generic"} expression defined.`); return; }
    try {
      const result = await jsonata(expression).evaluate(JSON.parse(state.dataset.context));
      publish(result === undefined ? "" : JSON.stringify(result, null, 2));
    } catch (failure) { publish("", failure instanceof Error ? failure.message : String(failure)); }
  };
  ctx.on<Event>(ctx.root, "input", event => {
    if (event.target instanceof HTMLTextAreaElement && ["genericExpression", "organizationExpression"].includes(event.target.name)) void preview();
  });
  ctx.onUpdate(() => { void preview(); });
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => {
    if (ctx.root.contains(event.detail.target)) void preview();
  });
  ctx.onUnmount(() => { disposed = true; revision++; });
  void preview();
}
