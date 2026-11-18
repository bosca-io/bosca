import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { resolve } from "node:path";

const require = createRequire(resolve("../../bml/bml-runtime/package.json"));
const { Window } = require("happy-dom");
const { build } = require("esbuild");
const fixtures = JSON.parse(await readFile("build/test-fixtures/components.json", "utf8"));
const bundle = await build({
  stdin: { contents: [
    "ComponentsProfileDetailsProfileDetailsPage", "ComponentsProfileAttributesProfileAttributesPage",
    "ComponentsProfileAttributesProfileAttributeActionsPage", "ComponentsProfileSharedProfileVisibilityMenuPage",
    "ComponentsSharedLocalDateTimePage", "ComponentsSharedToastMessagePage", "ComponentsSharedSignOutLinkPage",
  ].map(name => `import "./build/generated/bml/ts/${name}.ts";`).join("\n") + '\nexport { replaceChildrenFromHtml, notifyIslandUpdate, unmountAll } from "@bosca/bml";', resolveDir: process.cwd() },
  alias: { "@bosca/bml": resolve("../../bml/bml-runtime/src/index.ts") },
  bundle: true, write: false, format: "esm", platform: "browser",
});
let instance = 0;

async function fixture(html, check) {
  const window = new Window({ url: "http://profiles.test/profile" }), navigations = [];
  // Supply native option defaults and selection semantics for the isolated DOM fixture.
  if (!("defaultSelected" in window.HTMLOptionElement.prototype)) Object.defineProperty(window.HTMLOptionElement.prototype, "defaultSelected", {
    get() { return this.hasAttribute("selected"); },
  });
  Object.defineProperty(window.HTMLSelectElement.prototype, "selectedOptions", {
    get() {
      const options = [...this.options].filter(option => option.selected);
      options.item = index => options[index] ?? null;
      return options;
    },
  });
  for (const key of ["window", "document", "Document", "DOMParser", "Node", "NodeFilter", "Element", "HTMLElement", "HTMLDetailsElement", "HTMLScriptElement", "HTMLInputElement", "HTMLSelectElement", "HTMLTextAreaElement", "HTMLFormElement", "HTMLButtonElement", "HTMLDialogElement", "HTMLTimeElement", "Event", "CustomEvent", "MutationObserver"]) globalThis[key] = window[key];
  globalThis.location = { protocol: "http:", assign: href => navigations.push(href) };
  globalThis.auth = { async signOut() {}, destroy() {} };
  document.body.innerHTML = html;
  const disableActions = scope => {
    scope.querySelectorAll("[data-bml-method]").forEach(element => element.removeAttribute("data-bml-method"));
    scope.querySelectorAll("select").forEach(select => {
      select.value = [...select.options].find(option => option.hasAttribute("selected"))?.value ?? select.options[0]?.value ?? "";
    });
  };
  disableActions(document);
  const settle = () => new Promise(resolve => setTimeout(resolve, 0));
  const component = tag => document.querySelector(`[data-bml-component="${tag}"]`);
  const runtime = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString("base64")}#${instance++}`);
  await settle();
  const update = (target, html) => {
    const result = runtime.replaceChildrenFromHtml(target, html);
    disableActions(target);
    runtime.notifyIslandUpdate(result);
  };
  const submit = form => form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  try { await check({ window, component, update, submit, settle, navigations, runtime }); }
  finally {
    runtime.unmountAll(document);
    globalThis[Symbol.for("@bosca/bml/island-runtime")]?.observer?.disconnect();
    delete globalThis[Symbol.for("@bosca/bml/island-runtime")];
    await window.happyDOM.abort();
  }
}

test("standalone details tags initialize automatically and keep dirty state within their own instance", () => fixture(fixtures.details + fixtures.otherDetails, async ({ settle }) => {
  const [first, other] = document.querySelectorAll('[data-bml-component="profile-details"]');
  const input = first.querySelector('[name="name"]'), save = first.querySelector("[data-profile-save]");
  input.value = "Changed"; input.dispatchEvent(new Event("input", { bubbles: true }));
  assert.equal(save.disabled, false);
  assert.equal(other.querySelector("[data-profile-save]").disabled, true);
  input.value = input.defaultValue; input.dispatchEvent(new Event("input", { bubbles: true }));
  assert.equal(save.disabled, true);
  const search = first.querySelector('[name="searchable"]');
  search.checked = !search.defaultChecked; search.dispatchEvent(new Event("change", { bubbles: true }));
  assert.equal(save.disabled, false);
  first.remove(); await settle();
  save.disabled = true;
  input.value = "Detached"; input.dispatchEvent(new Event("input", { bubbles: true }));
  assert.equal(save.disabled, true, "unmount removes form listeners");
}));

test("attribute tags gate add/remove while dirty and serialize values before submission", () => fixture(fixtures.attributes, async ({ component, submit }) => {
  const root = component("profile-attributes"), form = root.querySelector("[data-profile-attributes-form]");
  const input = form.querySelector('[name="value"]'), remove = form.querySelector("[data-remove-attribute]");
  const add = root.querySelector("[data-open-attribute-modal]");
  input.value = "Changed"; input.dispatchEvent(new Event("input", { bubbles: true }));
  assert.equal(form.querySelector("[data-attributes-save]").disabled, false);
  assert.equal(remove.disabled, true); assert.equal(add.disabled, true);
  submit(form);
  assert.deepEqual(JSON.parse(form.elements.namedItem("attributes").value), [{ id: "attribute", typeId: "example", value: "Changed", visibility: "PUBLIC" }]);
  input.value = input.defaultValue; input.dispatchEvent(new Event("input", { bubbles: true }));
  assert.equal(remove.disabled, false); assert.equal(add.disabled, false);
  remove.click();
  const confirmation = root.querySelector("[data-remove-attribute-dialog]");
  assert.equal(confirmation.open, true);
  confirmation.dispatchEvent(new Event("cancel", { cancelable: true }));
  assert.equal(confirmation.open, false);
}));

test("attribute modal survives actual BML fragment updates, retains validation failures and closes after success", () => fixture(fixtures.attributes, async ({ component, update, submit, settle }) => {
  const root = component("profile-attributes"), view = root;
  const initial = view.innerHTML;
  root.querySelector("[data-open-attribute-modal]").click();
  assert.equal(root.querySelector("[data-attribute-modal]").open, true);
  update(view, fixtures.selectedAttribute); await settle();
  let modal = root.querySelector("[data-attribute-modal]");
  assert.equal(modal.open, true);
  assert.equal(modal.hasAttribute("data-has-selected-attribute-type"), true);
  submit(modal.querySelector("[data-add-attribute-form]"));
  update(view, fixtures.selectedAttribute); await settle();
  modal = root.querySelector("[data-attribute-modal]");
  assert.equal(modal.open, true, "a failed add retains the selected form and open dialog");
  submit(modal.querySelector("[data-add-attribute-form]"));
  update(view, initial); await settle();
  assert.equal(root.querySelector("[data-attribute-modal]").open, false);
  root.querySelector("[data-open-attribute-modal]").click();
  const current = root.querySelector("[data-attribute-modal]");
  current.dispatchEvent(new Event("cancel", { cancelable: true })); current.close();
  update(view, initial); await settle();
  assert.equal(root.querySelector("[data-attribute-modal]").open, false, "cancelled dialogs stay closed after updates");
}));

test("visibility and attribute menus own coordination, labels, outside dismissal and cleanup", () => fixture(fixtures.visibility + fixtures.actions, async ({ component, settle }) => {
  const visibility = component("profile-visibility-menu"), actions = component("profile-attribute-actions");
  visibility.open = true; visibility.dispatchEvent(new Event("toggle"));
  actions.open = true; actions.dispatchEvent(new Event("toggle"));
  assert.equal(visibility.open, false);
  const select = visibility.querySelector("select");
  visibility.open = true; select.value = "FRIENDS"; select.dispatchEvent(new Event("change", { bubbles: true }));
  assert.equal(visibility.open, false);
  assert.equal(visibility.querySelector("[data-visibility-label]").textContent, "Friends");
  actions.open = true; document.body.click(); assert.equal(actions.open, false);
  actions.remove(); await settle(); actions.open = true;
  document.body.click(); assert.equal(actions.open, true, "detached menus no longer receive document events");
}));

test("date tag localizes itself, updates its own timestamp and disconnects on unmount", () => fixture(fixtures.date, async ({ component, settle }) => {
  const time = component("local-date-time");
  const formatted = value => new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
  assert.equal(time.textContent, formatted(time.dateTime));
  time.dateTime = "2026-11-04T12:00:00Z"; await settle();
  assert.equal(time.textContent, formatted(time.dateTime));
  time.dateTime = "invalid"; await settle(); assert.equal(time.textContent, "invalid");
  time.remove(); await settle(); time.dateTime = "2020-01-01T00:00:00Z"; await settle();
  assert.equal(time.textContent, "invalid");
}));

test("toast tag emits new sequences without global action tracking, suppresses replay and disposes its notices", () => fixture(`<section>${fixtures.toast}</section>`, async ({ component, update, settle }) => {
  const source = component("toast-message");
  assert.equal(document.querySelector(".toast"), null, "initial rendered notices are a baseline");
  const notify = () => document.body.dispatchEvent(new CustomEvent("bml:update", { bubbles: true, detail: { target: document.body, added: [], retained: [source] } }));
  update(document.querySelector("section"), fixtures.toast.replace('data-toast-id="1"', 'data-toast-id="2"').replace('data-toast-message="Old notice"', 'data-toast-message="Saved"'));
  assert.equal(component("toast-message"), source, "the mounted tag survives the server fragment update");
  assert.equal(document.querySelectorAll(".toast").length, 1);
  notify(); assert.equal(document.querySelectorAll(".toast").length, 1);
  source.dataset.toastId = "3"; notify();
  assert.equal(document.querySelectorAll(".toast").length, 2, "repeated messages with new sequences are announced");
  document.querySelector(".toast-close").click();
  assert.equal(document.querySelector(".toast").classList.contains("toast-leaving"), true);
  source.remove(); await settle();
  assert.equal(document.querySelector(".toast-container"), null);
}));

test("sign-out links own their action and prevent duplicate requests", () => fixture(fixtures.signOut, async ({ component, navigations, settle }) => {
  let calls = 0, destroyed = 0, release;
  globalThis.auth = { signOut() { calls++; return new Promise(resolve => { release = resolve; }); }, destroy() { destroyed++; } };
  const link = component("sign-out-link");
  link.click(); link.click(); assert.equal(calls, 1);
  release(); await settle();
  assert.equal(destroyed, 1);
  assert.deepEqual(navigations, ["http://profiles.test/login"]);
}));
