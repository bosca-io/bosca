import { build } from "esbuild";
import { test } from "node:test";
import assert from "node:assert/strict";

const result = await build({
  stdin: {
    contents:
      'export * from "./src/main/client/auth"; export * from "@bosca/auth-client-browser/core"',
    resolveDir: process.cwd(),
  },
  bundle: true,
  write: false,
  format: "esm",
  platform: "browser",
});
const {
  createAuthFlow,
  authError,
  passwordStrength,
  AccountLinkRequiredError,
  InvalidCredentialsError,
  NetworkError,
} = await import(
  `data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString("base64")}`
);

function setup(search = "", overrides = {}) {
  const navigations = [];
  globalThis.location = {
    origin: "https://git.test",
    search,
    assign: (url) => navigations.push(url),
    replace: (url) => navigations.push(url),
  };
  const storage = new Map();
  globalThis.sessionStorage = {
    getItem: (key) => storage.get(key),
    setItem: (key, value) => storage.set(key, value),
    removeItem: (key) => storage.delete(key),
  };
  const auth = {
    isAuthenticated: false,
    initialize: async () => {},
    handleRedirectResult: async () => null,
    getLinkFromUrl: () => null,
    ...overrides,
  };
  const flow = createAuthFlow(auth, (path) =>
    path?.startsWith("/") && !path.startsWith("//") ? path : "/repositories",
  );
  return { flow, navigations, storage };
}

const email = " Person@Example.test ";
const password = "correct-password-123!";

test("friendly authentication errors and password feedback", () => {
  assert.match(
    authError(new InvalidCredentialsError(), "fallback"),
    /email or password/,
  );
  assert.match(
    authError(new NetworkError("internal host details"), "fallback"),
    /reach the server/,
  );
  assert.equal(
    authError(new Error("database details"), "Try again."),
    "Try again.",
  );
  assert.match(passwordStrength(""), /long, unique/);
  assert.match(passwordStrength("tiny"), /Weak/);
  assert.match(passwordStrength("a-long-password-123!"), /Strong/);
});

test("signup normalizes the email and stores typed name and email profile attributes", async () => {
  let input;
  let signins = 0;
  const fixture = setup("?redirect=%2Frepositories%2Frepo", {
    signUp: async (options) => {
      input = options;
      return { verified: false };
    },
    signInWithPassword: async () => {
      signins++;
    },
  });
  await fixture.flow.signUp(email, password, " Ada ", " Lovelace ");
  assert.equal(input.identifier, "person@example.test");
  assert.equal(input.originator, "git-ui");
  assert.equal(input.profile.name, "Ada Lovelace");
  assert.equal(input.profile.visibility, "USER");
  assert.deepEqual(
    input.profile.attributes.map((attribute) => attribute.typeId),
    ["bosca.profiles.name", "bosca.profiles.email"],
  );
  assert.equal(signins, 0);
  const url = new URL(fixture.navigations[0], location.origin);
  assert.equal(url.pathname, "/auth/verify");
  assert.equal(url.searchParams.get("redirect"), "/repositories/repo");
  assert.equal(fixture.storage.get("git-auth-email"), "person@example.test");
});

test("verified signup establishes a session before leaving", async () => {
  let signins = 0;
  const fixture = setup("", {
    signUp: async () => ({ verified: true }),
    signInWithPassword: async () => {
      signins++;
    },
  });
  await fixture.flow.signUp(email, password, "Ada", "Lovelace");
  assert.equal(signins, 1);
  assert.deepEqual(fixture.navigations, ["/repositories"]);
  const failure = setup("", {
    signUp: async () => ({ verified: true }),
    signInWithPassword: async () => {
      throw new NetworkError();
    },
  });
  await assert.rejects(
    failure.flow.signUp(email, password, "Ada", "Lovelace"),
    NetworkError,
  );
  assert.equal(failure.navigations.length, 0);
});

test("sign-in passes normalized credentials and leaves errors to the island", async () => {
  let credentials;
  const failure = new InvalidCredentialsError("internal reason");
  const fixture = setup("", {
    signInWithPassword: async (...args) => {
      credentials = args;
      throw failure;
    },
  });
  await assert.rejects(
    fixture.flow.signIn(email, password),
    (error) => error === failure,
  );
  assert.deepEqual(credentials, ["person@example.test", password, "git-ui"]);
  assert.match(fixture.flow.failure(failure, "fallback"), /email or password/);
  assert.equal(fixture.navigations.length, 0);
});

test("unverified sign-in preserves the email and return URL", async () => {
  const fixture = setup("?redirect=%2Frepositories%3Fstate%3Darchived", {
    signInWithPassword: async () => ({ principal: { verified: false } }),
  });
  await fixture.flow.signIn(email, password);
  const url = new URL(fixture.navigations[0], location.origin);
  assert.equal(url.pathname, "/auth/verify");
  assert.equal(url.searchParams.get("email"), "person@example.test");
  assert.equal(
    url.searchParams.get("redirect"),
    "/repositories?state=archived",
  );
});

test("account-link errors preserve the offered proof methods", async () => {
  const fixture = setup();
  const failure = new AccountLinkRequiredError("pending-token", ["EMAIL"]);
  assert.equal(fixture.flow.failure(failure, "fallback"), null);
  const url = new URL(fixture.navigations[0], location.origin);
  assert.equal(url.pathname, "/auth/link/confirm");
  assert.equal(url.searchParams.get("token"), "pending-token");
  assert.equal(url.searchParams.get("methods"), "EMAIL");
  assert.equal(fixture.flow.redirecting, true);
});

test("forgot password normalizes email and keeps account existence private", async () => {
  let identifier;
  const success = setup("", {
    forgotPassword: async (value) => {
      identifier = value;
    },
  });
  assert.equal(await success.flow.forgotPassword(email), undefined);
  assert.equal(identifier, "person@example.test");
  const failure = setup("", {
    forgotPassword: async () => {
      throw new Error("unknown account");
    },
  });
  assert.equal(await failure.flow.forgotPassword(email), undefined);
  assert.equal(failure.navigations.length, 0);
});

test("reset requires the emailed token and returns to login after success", async () => {
  let input;
  const missing = setup("", {
    resetPassword: async (...args) => {
      input = args;
    },
  });
  await assert.rejects(
    missing.flow.resetPassword(password),
    /Missing reset token/,
  );
  assert.equal(input, undefined);
  const fixture = setup("?token=reset&redirect=%2Frepositories%2Frepo", {
    resetPassword: async (...args) => {
      input = args;
    },
  });
  await fixture.flow.resetPassword(password);
  assert.deepEqual(input, ["reset", password]);
  const url = new URL(fixture.navigations[0], location.origin);
  assert.equal(url.pathname, "/login");
  assert.equal(url.searchParams.get("passwordReset"), "true");
  assert.equal(url.searchParams.get("redirect"), "/repositories/repo");
});

test("verification requires a token and returns to login after success", async () => {
  let token;
  const fixture = setup("?token=verification", {
    verifyEmail: async (value) => {
      token = value;
    },
  });
  await fixture.flow.verifyEmail();
  assert.equal(token, "verification");
  assert.match(fixture.navigations[0], /accountVerified=true/);
  const missing = setup();
  await assert.rejects(
    missing.flow.verifyEmail(),
    /Missing verification token/,
  );
});

test("email-only linking offers the email method and sends its token", async () => {
  let token;
  const fixture = setup("?token=pending&methods=EMAIL,UNKNOWN", {
    linkRequestEmailProof: async (value) => {
      token = value;
    },
  });
  assert.deepEqual(fixture.flow.linkMethods, ["EMAIL"]);
  await fixture.flow.requestEmailProof();
  assert.equal(token, "pending");
  assert.equal(fixture.navigations.length, 0);
});

test("password and emailed proof confirmations establish sessions before navigation", async () => {
  let input;
  const passwordFixture = setup("?token=pending", {
    linkConfirmPassword: async (...args) => {
      input = args;
    },
  });
  await passwordFixture.flow.confirmPassword(password);
  assert.deepEqual(input, ["pending", password]);
  assert.deepEqual(passwordFixture.navigations, ["/repositories"]);
  let proof;
  const emailFixture = setup("?proof=emailed", {
    linkConfirmEmail: async (value) => {
      proof = value;
    },
  });
  await emailFixture.flow.confirmEmail();
  assert.equal(proof, "emailed");
  assert.deepEqual(emailFixture.navigations, ["/repositories"]);
});

test("incomplete account-link requests never call the SDK", async () => {
  const fixture = setup();
  await assert.rejects(
    fixture.flow.confirmPassword(password),
    /Missing account-link token/,
  );
  await assert.rejects(
    fixture.flow.confirmEmail(),
    /Missing account-link proof/,
  );
  await assert.rejects(
    fixture.flow.requestEmailProof(),
    /Missing account-link token/,
  );
});

test("OAuth exchange finishes the session and uses same-origin provider URLs", async () => {
  const fixture = setup("?redirect=%2Frepositories%2Frepo", {
    handleRedirectResult: async () => ({ principal: { verified: true } }),
  });
  assert.equal(await fixture.flow.ready, null);
  assert.deepEqual(fixture.navigations, ["/repositories/repo"]);
  const url = new URL(fixture.flow.googleUrl(), location.origin);
  assert.equal(url.origin, location.origin);
  assert.equal(url.pathname, "/oauth2/google/login");
  assert.equal(url.searchParams.get("originator"), "git-ui");
  assert.equal(new URL(url.searchParams.get("redirect")).pathname, "/login");
});

test("OAuth failures are returned for the island to display", async () => {
  const failure = new Error("invalid exchange token");
  const fixture = setup("", {
    handleRedirectResult: async () => {
      throw failure;
    },
  });
  assert.equal(await fixture.flow.ready, failure);
  assert.equal(
    fixture.flow.failure(failure, "Sign-in could not be completed."),
    "Sign-in could not be completed.",
  );
  assert.equal(fixture.navigations.length, 0);
});

test("resend uses the pending verification email", async () => {
  let identifier;
  const fixture = setup("?email=pending%40example.test", {
    resendVerification: async (value) => {
      identifier = value;
    },
  });
  assert.equal(fixture.flow.email, "pending@example.test");
  await fixture.flow.resendVerification();
  assert.equal(identifier, "pending@example.test");
  assert.equal(setup("?token=invalid").flow.email, "");
});
