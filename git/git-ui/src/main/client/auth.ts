import {
  AccountLinkRequiredError,
  BoscaAuthError,
} from "@bosca/auth-client-browser/core";
import type {
  BoscaAuth,
  LinkProofMethod,
} from "@bosca/auth-client-browser/core";

export function authError(error: unknown, fallback: string): string {
  if (error instanceof BoscaAuthError) {
    if (error.code === "auth/invalid-credentials")
      return "The email or password you entered is incorrect.";
    if (error.code === "auth/email-already-registered")
      return "An account already exists for this email. Sign in or reset your password.";
    if (error.code === "auth/network-error")
      return "We couldn't reach the server. Check your connection and try again.";
  }
  return fallback;
}

export function passwordStrength(password: string): string {
  if (!password) return "Use a long, unique password.";
  const score =
    Number(password.length >= 8) +
    Number(password.length >= 12) +
    Number(/\d/.test(password)) +
    Number(/[^a-zA-Z0-9]/.test(password));
  return `Password strength: ${["Weak", "Weak", "Fair", "Good", "Strong"][score]}`;
}

/** Shared SDK operations and navigation. BML owns each form's setup, events and lifecycle. */
export function createAuthFlow(
  auth: BoscaAuth,
  returnPath: (path: string | null) => string,
) {
  const params = new URLSearchParams(location.search);
  const stored = (key: string) => {
    try {
      return sessionStorage.getItem(key);
    } catch {
      return null;
    }
  };
  const remember = (key: string, value: string) => {
    try {
      sessionStorage.setItem(key, value);
    } catch {
      /* Browsers may disable session storage. */
    }
  };
  const destination = returnPath(
    params.get("redirect") ?? stored("git-auth-return"),
  );
  let redirecting = false;
  if (params.has("redirect")) remember("git-auth-return", destination);
  const url = (path: string, values: Record<string, string> = {}) =>
    `${path}?${new URLSearchParams({ redirect: destination, ...values })}`;
  const finish = () => {
    redirecting = true;
    try {
      sessionStorage.removeItem("git-auth-return");
      sessionStorage.removeItem("git-auth-email");
    } catch {
      /* Session storage is optional. */
    }
    location.replace(destination);
  };
  const link = (token: string, methods: readonly LinkProofMethod[] | null) => {
    redirecting = true;
    location.assign(
      url("/auth/link/confirm", {
        token,
        methods: (methods ?? ["PASSWORD"]).join(","),
      }),
    );
  };
  const verification = (email: string) => {
    remember("git-auth-email", email);
    location.assign(url("/auth/verify", { email }));
  };
  const initialize = async () => {
    const challenge = auth.getLinkFromUrl();
    if (challenge) {
      link(challenge.token, challenge.methods);
      return;
    }
    const needsIdentity = location.pathname === "/login" || location.pathname.startsWith("/auth/");
    await auth.initialize(needsIdentity);
    if (await auth.handleRedirectResult()) finish();
    const providerError = params.get("error");
    if (providerError) throw new Error("OAuth sign-in was not completed");
  };
  const ready = initialize().then(
    () => null,
    (error: unknown) => error,
  );

  const normalizeEmail = (value: string) => value.trim().toLocaleLowerCase();

  return {
    ready,
    params,
    url,
    finish,
    get redirecting() {
      return redirecting;
    },
    get email() {
      return params.get("email") ?? stored("git-auth-email") ?? "";
    },
    get linkMethods() {
      return (params.get("methods") ?? "PASSWORD")
        .split(",")
        .filter((method) => method === "PASSWORD" || method === "EMAIL");
    },
    failure(reason: unknown, fallback: string): string | null {
      if (reason instanceof AccountLinkRequiredError) {
        link(reason.token, reason.methods);
        return null;
      }
      return authError(reason, fallback);
    },
    googleUrl: () =>
      `/oauth2/google/login?${new URLSearchParams({ redirect: new URL(url("/login"), location.origin).href, originator: "git-ui" })}`,
    async signIn(identifier: string, password: string) {
      await ready;
      const email = normalizeEmail(identifier);
      try {
        const response = await auth.signInWithPassword(
          email,
          password,
          "git-ui",
        );
        if (!response.principal.verified) {
          verification(email);
          return;
        }
        finish();
      } catch (reason) {
        if (
          reason instanceof BoscaAuthError &&
          ["auth/principal-not-verified", "auth/email-not-verified"].includes(
            reason.code,
          )
        ) {
          verification(email);
          return;
        }
        throw reason;
      }
    },
    async signUp(
      identifier: string,
      password: string,
      firstName: string,
      lastName: string,
    ) {
      await ready;
      const email = normalizeEmail(identifier);
      const name = `${firstName.trim()} ${lastName.trim()}`.trim();
      const attribute = (
        typeId: string,
        attributes: Record<string, unknown>,
      ) => ({
        typeId,
        attributes,
        source: "signup",
        priority: 1,
        confidence: 100,
        visibility: "USER" as const,
      });
      const principal = await auth.signUp({
        identifier: email,
        password,
        originator: "git-ui",
        profile: {
          name,
          visibility: "USER",
          attributes: [
            attribute("bosca.profiles.name", { name }),
            attribute("bosca.profiles.email", { email }),
          ],
        },
      });
      if (!principal.verified) {
        verification(email);
        return;
      }
      // Signup creates the principal; sign-in establishes the browser session.
      await auth.signInWithPassword(email, password, "git-ui");
      finish();
    },
    async forgotPassword(identifier: string) {
      await ready;
      try {
        await auth.forgotPassword(normalizeEmail(identifier));
      } catch {
        /* Match Studio's account-recovery privacy contract. */
      }
    },
    async resetPassword(password: string) {
      await ready;
      const token = params.get("token");
      if (!token) throw new Error("Missing reset token");
      await auth.resetPassword(token, password);
      location.assign(url("/login", { passwordReset: "true" }));
    },
    async verifyEmail() {
      await ready;
      const token = params.get("token");
      if (!token) throw new Error("Missing verification token");
      await auth.verifyEmail(token);
      location.replace(url("/login", { accountVerified: "true" }));
    },
    async resendVerification() {
      await ready;
      await auth.resendVerification(
        params.get("email") ?? stored("git-auth-email") ?? "",
      );
    },
    async confirmPassword(password: string) {
      await ready;
      const token = params.get("token");
      if (!token) throw new Error("Missing account-link token");
      await auth.linkConfirmPassword(token, password);
      finish();
    },
    async confirmEmail() {
      await ready;
      const proof = params.get("proof");
      if (!proof) throw new Error("Missing account-link proof");
      await auth.linkConfirmEmail(proof);
      finish();
    },
    async requestEmailProof() {
      await ready;
      const token = params.get("token");
      if (!token) throw new Error("Missing account-link token");
      await auth.linkRequestEmailProof(token);
    },
  };
}
