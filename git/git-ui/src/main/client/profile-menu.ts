import type { ClientContext } from "@bosca/bml";
import { savedAppearance, setAppearance } from "./appearance";

/** Account actions inside the shared, viewport-fitted popover and its native disclosure. */
export function mountProfileMenu(ctx: ClientContext) {
  const panel = ctx.root.querySelector<HTMLDetailsElement>("details");
  const trigger = panel?.querySelector("summary");
  const appearance = ctx.ref<HTMLButtonElement>("appearance");
  const label = ctx.ref<HTMLElement>("appearanceLabel");
  const lightIcon = ctx.ref<HTMLElement>("appearanceLightIcon");
  const darkIcon = ctx.ref<HTMLElement>("appearanceDarkIcon");
  const signout = ctx.ref<HTMLButtonElement>("signout");
  const name = ctx.root.querySelector<HTMLElement>("[data-profile-heading] [data-profile-name]");
  const slug = ctx.root.querySelector<HTMLElement>("[data-profile-heading] [data-profile-subtitle]");
  const error = ctx.root.querySelector<HTMLElement>("[data-profile-error]");
  if (!panel || !trigger || !appearance || !label || !lightIcon || !darkIcon || !signout || !name || !slug || !error) return;
  const restoreAppearance = () => {
    const value = savedAppearance();
    setAppearance(value, false);
    label.textContent = value === "dark" ? "Light mode" : "Dark mode";
    lightIcon.hidden = value !== "dark";
    darkIcon.hidden = value !== "light";
    appearance.setAttribute("aria-label", value === "dark" ? "Switch to light mode" : "Switch to dark mode");
  };
  ctx.on(appearance, "click", () => {
    setAppearance(savedAppearance() === "dark" ? "light" : "dark");
    restoreAppearance();
  });
  ctx.on(window, "pageshow", restoreAppearance);
  restoreAppearance();
  appearance.disabled = false;
  signout.disabled = false;
  let profileLoaded = false, loadingProfile = false;
  const loadProfile = async () => {
    if (!panel.open || profileLoaded || loadingProfile) return;
    loadingProfile = true;
    error.hidden = true;
    try {
      await gitAuthFlow.ready;
      const principal = gitAuth.currentUser ?? await gitAuth.initialize(true);
      if (!ctx.root.isConnected) return;
      if (!principal) throw new Error("Your profile could not be loaded.");
      const profile = gitAuth.currentProfile;
      name.textContent = profile?.name || "Profile";
      slug.textContent = profile?.slug ? `@${profile.slug}` : "Signed in";
      trigger.setAttribute("aria-label", profile?.name ? `Profile: ${profile.name}` : "Profile");
      profileLoaded = true;
    } catch {
      if (ctx.root.isConnected) {
        error.textContent = "Your profile could not be loaded. Reopen the menu to retry.";
        error.hidden = false;
      }
    } finally {
      loadingProfile = false;
    }
  };
  ctx.on<ToggleEvent>(panel, "toggle", event => {
    if (event.target === panel) void loadProfile();
  });
  ctx.on(signout, "click", async () => {
    signout.disabled = true;
    signout.setAttribute("aria-busy", "true");
    error.hidden = true;
    try {
      await gitAuth.signOut();
      location.assign("/login");
    } catch {
      signout.disabled = false;
      signout.removeAttribute("aria-busy");
      error.textContent = "Sign out failed. Try again.";
      error.hidden = false;
    }
  });
  void loadProfile();
}
