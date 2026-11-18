import { BoscaAuth } from "@bosca/auth-client-browser/core";
import { authReturnPath, createAuthFlow } from "./auth";

declare global {
  var boscaAuth: BoscaAuth;
  var authReturnPath: (requested: string | null) => string;
  var bmlConfig: { authCookiePrefix?: string } | undefined;
  var boscaAuthFlow: ReturnType<typeof createAuthFlow>;
}

/** Starts the hosting application's shared browser session after BML installs its cookie configuration. */
export function initializeAuth(originator: string): void {
  globalThis.boscaAuth = new BoscaAuth({
    apiUrl: "",
    storage: "cookie",
    tokenName: globalThis.bmlConfig?.authCookiePrefix,
  });
  globalThis.authReturnPath = authReturnPath;
  globalThis.boscaAuthFlow = createAuthFlow(boscaAuth, authReturnPath, originator);
}
