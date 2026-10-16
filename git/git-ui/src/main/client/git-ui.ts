import { BoscaAuth } from "@bosca/auth-client-browser/core";
import { createAuthFlow } from "./auth";

declare global {
  var gitAuth: BoscaAuth;
  var gitReturnPath: (requested: string | null) => string;
  var bmlConfig: { authCookiePrefix?: string } | undefined;
  var gitAuthFlow: ReturnType<typeof createAuthFlow>;
}

globalThis.gitAuth = new BoscaAuth({
  apiUrl: "",
  storage: "cookie",
  tokenName: globalThis.bmlConfig?.authCookiePrefix,
});

globalThis.gitReturnPath = (requested) => {
  if (!requested?.startsWith("/")) return "/repositories";
  try {
    const destination = new URL(requested, location.origin);
    if (destination.origin === location.origin)
      return destination.pathname + destination.search + destination.hash;
  } catch {
    // An invalid return path falls back to the repository list.
  }
  return "/repositories";
};

globalThis.gitAuthFlow = createAuthFlow(gitAuth, gitReturnPath);
