import assert from "node:assert/strict";
import test from "node:test";
import { createRequire } from "node:module";
const require = createRequire(new URL("../../../bml-runtime/package.json", import.meta.url));
const { Window } = require("happy-dom");
const { build } = require("esbuild");

const bundle = await build({
  stdin: { contents: 'import "./build/generated/bml/ts/ComponentsProfileMenuPage.ts"; export { unmountAll } from "@bosca/bml";', resolveDir: process.cwd() },
  bundle: true, write: false, format: "esm", platform: "browser",
  alias: { "@client": `${process.cwd()}/src/main/client`, "@bosca/bml": `${process.cwd()}/../bml-runtime/src/index.ts` },
});
let instance = 0;
const tag = "bml-696f2e626f7363613a626d6c2d64657369676e-profile-menu";
const markup = (options = "") => `<div data-bml-component="${tag}" ${options}><div data-bml-design-profile-menu><details><summary><span data-popover-label>Profile</span></summary><div data-profile-heading><strong data-profile-name>Profile</strong><span data-profile-subtitle>Signed in</span></div><button data-bml-ref="appearance" disabled><span data-bml-ref="appearanceLightIcon"></span><span data-bml-ref="appearanceDarkIcon" hidden></span><span data-bml-ref="appearanceLabel">Light mode</span></button><button data-bml-ref="signout" disabled>Sign out</button><p data-profile-error hidden></p></details></div></div>`;

async function fixture(options, check) {
  const window = new Window({ url: "http://profile.test/" }), navigations = [];
  for (const key of ["window", "document", "Node", "Element", "HTMLElement", "HTMLDetailsElement", "HTMLScriptElement", "HTMLInputElement", "HTMLFormElement", "HTMLButtonElement", "HTMLSelectElement", "HTMLTextAreaElement", "Event", "CustomEvent", "MutationObserver"]) globalThis[key] = window[key];
  globalThis.location = { protocol: "http:", assign: path => navigations.push(path) };
  globalThis.boscaAuthFlow = { ready: Promise.resolve() };
  document.body.innerHTML = markup(options);
  const root = document.body.firstElementChild, ref = name => root.querySelector(`[data-bml-ref="${name}"]`);
  const settle = () => new Promise(resolve => setTimeout(resolve, 0));
  let runtime;
  const hydrate = async () => {
    runtime = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString("base64")}#${instance++}`);
    await settle();
  };
  try { await check({ root, ref, hydrate, settle, navigations }); }
  finally {
    runtime?.unmountAll(document);
    globalThis[Symbol.for("@bosca/bml/island-runtime")]?.observer?.disconnect();
    delete globalThis[Symbol.for("@bosca/bml/island-runtime")];
    await window.happyDOM.abort();
  }
}

test("compiled account tag initializes identity and appearance without a consumer mount call", () => fixture('data-default-appearance="light" data-show-identity', async ({ root, ref, hydrate, settle, navigations }) => {
  let reads = 0;
  globalThis.boscaAuth = { currentUser: null, currentProfile: null, async initialize(fetchProfile) { assert.equal(fetchProfile, true); reads++; this.currentProfile = { name: "Jamie", slug: "jamie" }; return this.currentUser = { id: "principal" }; }, async signOut() {} };
  await hydrate();
  assert.equal(reads, 1);
  assert.equal(root.querySelector("[data-popover-label]").textContent, "Jamie");
  assert.equal(root.querySelector("[data-profile-subtitle]").textContent, "@jamie");
  assert.equal(document.documentElement.dataset.theme, "light");
  ref("appearance").click();
  assert.equal(document.documentElement.dataset.theme, "dark");
  assert.equal(ref("appearanceLabel").textContent, "Light mode");
  ref("signout").click(); await settle();
  assert.deepEqual(navigations, ["/login"]);
}));

test("default account tag loads identity on opening and survives dynamic replacement", () => fixture("", async ({ root, ref, hydrate, settle }) => {
  let reads = 0;
  globalThis.boscaAuth = { currentUser: null, currentProfile: null, async initialize() { reads++; this.currentProfile = { name: "Jamie" }; return this.currentUser = { id: "principal" }; } };
  await hydrate();
  assert.equal(reads, 0);
  assert.equal(document.documentElement.dataset.theme, "dark");
  const panel = root.querySelector("details"); panel.open = true; panel.dispatchEvent(new Event("toggle")); await settle();
  assert.equal(reads, 1);
  assert.equal(root.querySelector("[data-profile-name]").textContent, "Jamie");
  const oldAction = ref("appearance");
  root.remove(); await settle();
  document.body.insertAdjacentHTML("beforeend", markup()); await settle();
  const action = document.querySelector('[data-bml-ref="appearance"]');
  assert.equal(action.disabled, false);
  action.click();
  assert.equal(document.documentElement.dataset.theme, "light");
  oldAction.click();
  assert.equal(document.documentElement.dataset.theme, "light", "removed component listeners are released");
}));
