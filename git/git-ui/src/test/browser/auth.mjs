import { writeFile } from "node:fs/promises";
import assert from "node:assert/strict";
const targets = await (
  await fetch(`${process.env.GIT_UI_CDP_URL}/json`)
).json();
const ws = new WebSocket(
  targets.find((t) => t.type === "page").webSocketDebuggerUrl,
);
await new Promise((resolve) =>
  ws.addEventListener("open", resolve, { once: true }),
);
let sequence = 0;
const pending = new Map();
const exceptions = [];
ws.addEventListener("message", (event) => {
  const message = JSON.parse(event.data);
  if (message.method === "Runtime.exceptionThrown")
    exceptions.push(message.params.exceptionDetails);
  if (message.id) {
    const item = pending.get(message.id);
    pending.delete(message.id);
    if (message.error) item.reject(new Error(JSON.stringify(message.error)));
    else item.resolve(message.result);
  }
});
function call(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const result = await call("Runtime.evaluate", {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  if (result.exceptionDetails)
    throw new Error(JSON.stringify(result.exceptionDetails));
  return result.result.value;
}
async function until(expression) {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (await evaluate(expression)) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("Timed out: " + expression);
}
const base = process.env.GIT_UI_BASE_URL;
if (!base) throw Error("Set GIT_UI_BASE_URL to the isolated browser fixture.");
const port = new URL(base).port;
await call("Runtime.enable");
await call("Page.enable");
await call("Network.enable");
await call("Network.clearBrowserCookies");
await call("Emulation.setDeviceMetricsOverride", {
  width: 1440,
  height: 900,
  deviceScaleFactor: 1,
  mobile: false,
});
async function navigate(path, selector = "h1") {
  await call("Page.navigate", { url: base + path });
  await until(
    `location.href === ${JSON.stringify(base + path)} && document.readyState === 'complete' && document.querySelector(${JSON.stringify(selector)}) && typeof gitAuthFlow !== 'undefined'`,
  );
}
async function fill(values) {
  await evaluate(
    `Object.entries(${JSON.stringify(values)}).forEach(([name,value]) => { const input=document.querySelector('[name="'+name+'"]'); input.value=value; input.dispatchEvent(new Event('input',{bubbles:true})) })`,
  );
}
async function submit() {
  await evaluate('document.querySelector("button[type=submit]").click()');
}
async function signout() {
  await until(
    'document.readyState === "complete" && document.querySelector("[data-bml-ref=signout]") && !document.querySelector("[data-bml-ref=signout]").disabled',
  );
  await evaluate(
    'Array.from(document.querySelectorAll("button")).find(b=>b.textContent==="Sign out").click()',
  );
  await until(
    'location.pathname === "/login" && document.readyState === "complete" && document.querySelector(".auth-form")',
  );
}
await navigate("/login");
await fill({ identifier: "alice@example.test", password: "invalid" });
await submit();
await until(`
  document.body.innerText.includes("email or password you entered is incorrect") &&
  !document.querySelector("button[type=submit]").disabled
`);
assert.equal(
  await evaluate('document.querySelector("form").hasAttribute("aria-busy")'),
  false,
);
assert.equal(
  await evaluate('document.querySelector("button[type=submit]").textContent'),
  "Sign in",
);
assert.equal(await evaluate("location.pathname"), "/login");

await navigate("/auth/reset-password");
await until('document.body.innerText.includes("missing its token")');
assert.equal(await evaluate('document.querySelector("form").hidden'), true);

await navigate("/auth/link/confirm");
await until('document.body.innerText.includes("link is incomplete")');
assert.equal(await evaluate('document.querySelector("form").hidden'), true);
assert.equal(
  await evaluate('document.querySelector("[data-bml-ref=emailProof]").hidden'),
  true,
);

await navigate("/auth/verify");
assert.equal(
  await evaluate('document.querySelector("[data-bml-ref=resend]").disabled'),
  true,
);

await navigate("/login?error=access_denied");
await until(
  'document.body.innerText.includes("Sign-in could not be completed")',
);

await navigate("/auth/signup?redirect=%2Frepositories%2Frepo");
const primary = await evaluate(
  'getComputedStyle(document.querySelector(".auth-primary")).backgroundImage',
);
assert.match(primary, /linear-gradient/);
await fill({
  firstName: "Ada",
  lastName: "Lovelace",
  identifier: "new@example.test",
  password: "long-password-123!",
});
assert.match(
  await evaluate('document.querySelector(".password-strength").textContent'),
  /Strong/,
);
const shot = await call("Page.captureScreenshot", { format: "png" });
await writeFile(
  `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-signup.png`,
  Buffer.from(shot.data, "base64"),
);
await submit();
await until(
  'location.pathname === "/auth/verify" && document.readyState === "complete" && document.body.innerText.includes("new@example.test")',
);
await evaluate('document.querySelector("[data-bml-ref=resend]").click()');
await until(
  'document.querySelector("[data-bml-ref=resend]").disabled && /Resend in/.test(document.querySelector("[data-bml-ref=resend]").textContent)',
);
await call("Page.navigate", { url: base + "/auth/verify?token=verification" });
await until(
  'location.pathname === "/login" && document.body.innerText.includes("Email verified")',
);
assert.equal(
  new URLSearchParams(await evaluate("location.search")).get("redirect"),
  "/repositories/repo",
);
await fill({ identifier: "new@example.test", password: "long-password-123!" });
await submit();
await until(
  'location.pathname === "/repositories/repo" && document.querySelector(".tree")',
);
await signout();
await navigate("/auth/signup?redirect=%2Frepositories");
await fill({
  firstName: "Ada",
  lastName: "Lovelace",
  identifier: "verified@example.test",
  password: "long-password-123!",
});
await submit();
await until(
  'location.pathname === "/repositories" && document.querySelector(".repository-list")',
);
await signout();
await navigate("/auth/signup?redirect=%2Frepositories");
await fill({
  firstName: "Ada",
  lastName: "Lovelace",
  identifier: "linked@example.test",
  password: "long-password-123!",
});
await submit();
await until(
  'location.pathname === "/auth/link/confirm" && document.readyState === "complete" && document.querySelector("[data-bml-ref=emailProof]") && !document.querySelector("[data-bml-ref=emailProof]").hidden',
);
assert.equal(
  await evaluate('getComputedStyle(document.querySelector("form")).display'),
  "none",
);
await evaluate('document.querySelector("[data-bml-ref=emailProof]").click()');
await until('document.body.innerText.includes("Check your email for a link")');
await call("Page.navigate", { url: base + "/auth/link/confirm?proof=emailed" });
await until(
  'location.pathname === "/repositories" && document.querySelector(".repository-list")',
);
await signout();
await navigate("/auth/forgot");
await fill({ identifier: "new@example.test" });
await submit();
await until('document.body.innerText.includes("If an account exists")');
assert.equal(
  await evaluate('getComputedStyle(document.querySelector("form")).display'),
  "none",
);
await navigate("/auth/reset-password?token=reset");
await fill({
  password: "new-long-password-123!",
  confirmPassword: "different",
});
await submit();
await until('document.body.innerText.includes("passwords don\'t match")');
await fill({ confirmPassword: "new-long-password-123!" });
await submit();
await until(
  'location.pathname === "/login" && document.body.innerText.includes("Password reset")',
);
await fill({
  identifier: "pending@example.test",
  password: "long-password-123!",
});
await submit();
await until(
  'location.pathname === "/auth/verify" && document.body.innerText.includes("pending@example.test")',
);
await navigate("/login?redirect=%2Frepositories%3Fstate%3Darchived");
const oauth = new URL(
  await evaluate('document.querySelector(".provider-btn").href'),
);
assert.equal(oauth.pathname, "/oauth2/google/login");
assert.equal(oauth.origin, base);
const callback = new URL(oauth.searchParams.get("redirect"));
assert.equal(callback.pathname, "/login");
assert.equal(
  callback.searchParams.get("redirect"),
  "/repositories?state=archived",
);
await call("Page.navigate", {
  url:
    base +
    "/login?exchangeToken=oauth&redirect=%2Frepositories%3Fstate%3Darchived",
});
await until(
  'location.pathname === "/repositories" && location.search === "?state=archived" && document.querySelector(".repository-list")',
);
await signout();
await call("Emulation.setDeviceMetricsOverride", {
  width: 390,
  height: 844,
  deviceScaleFactor: 1,
  mobile: true,
});
await navigate("/auth/signup");
assert.ok(await evaluate("document.documentElement.scrollWidth <= innerWidth"));
assert.ok(
  await evaluate(
    'document.querySelector(".auth-card").getBoundingClientRect().width <= innerWidth-32',
  ),
);
const mobile = await call("Page.captureScreenshot", { format: "png" });
await writeFile(
  `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-signup-mobile.png`,
  Buffer.from(mobile.data, "base64"),
);
assert.equal(exceptions.length, 0, JSON.stringify(exceptions));
console.log(
  JSON.stringify({
    result: "passed",
    checks: [
      "primary button styling",
      "sign-in error and restored submit controls",
      "missing reset and account-link recovery",
      "verification without an email",
      "OAuth failure feedback",
      "signup and password feedback",
      "email verification and cooldown",
      "verified signup session",
      "email-only linking",
      "emailed proof session",
      "password recovery privacy",
      "password reset validation",
      "unverified sign-in",
      "Google callback URL",
      "OAuth exchange",
      "mobile layout",
      "no runtime exceptions",
    ],
  }),
);
ws.close();
