/** The hosting Atlas application supplies the canonical browser auth client and initialization flow. */
type WorkOpsBrowserSession = {
  getToken(): Promise<string | null>;
  on(event: string, callback: () => void): () => void;
};

export const workOpsBrowser = globalThis as typeof globalThis & {
  boscaAuth?: WorkOpsBrowserSession;
  boscaAuthFlow?: { ready: Promise<unknown> };
};
