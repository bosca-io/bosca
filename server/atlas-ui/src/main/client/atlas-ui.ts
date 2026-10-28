import { BoscaAuth } from "@bosca/auth-client-browser/core";
import { atlasReturnPath, createAuthFlow } from "./auth";

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

globalThis.atlasReturnPath = atlasReturnPath;

globalThis.atlasAuthFlow = createAuthFlow(atlasAuth, atlasReturnPath);
