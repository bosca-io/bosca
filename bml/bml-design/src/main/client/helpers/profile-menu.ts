import type { ClientContext } from "@bosca/bml";

/** Owns account actions and context activation for the shared profile-menu tag. */
export function profileMenu(ctx: ClientContext) {
  const panel = ctx.root.querySelector<HTMLDetailsElement>("details");
  const trigger = panel?.querySelector("summary");
  if (!panel || !trigger) return;
  const firstItem = () => ctx.root.querySelector<HTMLElement>('[role="menuitem"]:not(:disabled)');
  let focusOnOpen = false;
  const open = () => {
    if (panel.open) firstItem()?.focus({ preventScroll: true });
    else { focusOnOpen = true; panel.open = true; }
  };
  ctx.on<ToggleEvent>(panel, "toggle", event => {
    if (event.target !== panel || !panel.open || !focusOnOpen) return;
    focusOnOpen = false;
    requestAnimationFrame(() => {
      if (ctx.root.isConnected && panel.open) firstItem()?.focus({ preventScroll: true });
    });
  });
  ctx.on<MouseEvent>(trigger, "contextmenu", event => { event.preventDefault(); open(); });
  ctx.on<KeyboardEvent>(trigger, "keydown", event => {
    if (event.key === "ContextMenu" || (event.shiftKey && event.key === "F10")) {
      event.preventDefault(); open();
    }
  });
  accountActions(ctx);
}

// The host initializes the existing Bosca browser session; the menu consumes that instance.
const profileHost = globalThis as typeof globalThis & {
  boscaAuth?: {
    currentUser?: unknown;
    currentProfile?: { name?: string; slug?: string | null } | null;
    initialize(force?: boolean): Promise<unknown>;
    signOut(): Promise<unknown>;
  };
  boscaAuthFlow?: { ready: Promise<unknown> };
};

type Appearance = "light" | "dark";
const preference = "git_ui_appearance";

/** Read the same validated preference used by the server's initial document. */
function savedAppearance(): Appearance {
  const value = document.cookie.split(";").map(cookie => cookie.trim())
    .find(cookie => cookie.startsWith(`${preference}=`))?.slice(preference.length + 1);
  return value === "light" ? "light" : "dark";
}

/** Apply consumer CSS immediately and retain the preference for subsequent server renders. */
function setAppearance(value: Appearance, remember = true) {
  if (remember) document.cookie = `${preference}=${value}; Path=/; Max-Age=31536000; SameSite=Lax${location.protocol === "https:" ? "; Secure" : ""}`;
  document.documentElement.dataset.theme = value;
  document.body.dataset.theme = value;
}

/** Account actions inside the shared, viewport-fitted popover and its native disclosure. */
function accountActions(ctx: ClientContext) {
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
      await profileHost.boscaAuthFlow?.ready;
      const auth = profileHost.boscaAuth;
      if (!auth) throw new Error("Authentication is not initialized.");
      const principal = auth.currentUser ?? await auth.initialize(true);
      if (!ctx.root.isConnected) return;
      if (!principal) throw new Error("Your profile could not be loaded.");
      const profile = auth.currentProfile;
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
      const auth = profileHost.boscaAuth;
      if (!auth) throw new Error("Authentication is not initialized.");
      await auth.signOut();
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
