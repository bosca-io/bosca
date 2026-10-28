import { BoscaAuth } from "@bosca/auth-client-browser/core";
import { createAuthFlow } from "./auth";

declare global {
  var atlasAuth: BoscaAuth;
  var atlasReturnPath: (requested: string | null) => string;
  var bmlConfig: { authCookiePrefix?: string } | undefined;
  var atlasAuthFlow: ReturnType<typeof createAuthFlow>;
}

globalThis.atlasAuth = new BoscaAuth({
  apiUrl: "",
  storage: "cookie",
  tokenName: globalThis.bmlConfig?.authCookiePrefix,
});

globalThis.atlasReturnPath = (requested) => {
  if (!requested?.startsWith("/")) return "/";
  try {
    const destination = new URL(requested, location.origin);
    if (destination.origin === location.origin)
      return destination.pathname + destination.search + destination.hash;
  } catch {
    // An invalid return path falls back to the Atlas landing route.
  }
  return "/";
};

globalThis.atlasAuthFlow = createAuthFlow(atlasAuth, atlasReturnPath);
