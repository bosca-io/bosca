import assert from "node:assert/strict";
import test from "node:test";
import { createRequire } from "node:module";
const require = createRequire(new URL("../../../bml-runtime/package.json", import.meta.url));
const { Window } = require("happy-dom");
const { build } = require("esbuild");
const bundle = await build({
  stdin: { contents: 'export * from "./src/main/client/helpers/schema-form-data.ts"; export * from "./src/main/client/helpers/schema-form.ts";', resolveDir: process.cwd() },
  bundle: true, write: false, format: "esm", platform: "browser", loader: { ".css": "empty" },
});
const api = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString("base64")}`);

test("schema validation retains types and addresses nested required and format failures", () => {
  const schema = { type: "object", properties: { details: { type: "object", properties: { email: { type: "string", format: "email" } }, required: ["email"] }, amount: { type: "integer", minimum: 1 }, active: { type: "boolean" } } };
  const validate = api.formValidator(schema);
  assert.deepEqual(Object.keys(api.formErrors(validate, { details: {}, amount: 0, active: false })).sort(), ["amount", "details.email"]);
  assert.equal(api.formErrors(validate, { details: { email: "bad" } })["details.email"], 'must match format "email"');
  assert.deepEqual(api.formErrors(validate, { details: { email: "person@example.test" }, amount: 3, active: false }), {});
  assert.equal(api.formControlValue("2", { type: "integer" }), 2);
  assert.equal(api.formControlValue("false", { enum: [true, false] }), false);
  assert.equal(api.formControlValue("", { type: "number" }), undefined);
  assert.deepEqual(api.formControlValue(["one", "two"], { type: "array" }), ["one", "two"]);
  const data = { hidden: "retain", details: { city: "Old" } };
  api.setFormValue(data, "details.city", "New");
  assert.deepEqual(data, { hidden: "retain", details: { city: "New" } });
  api.setFormValue(data, "__proto__.scope", "local");
  assert.equal({}.scope, undefined);
  assert.equal(api.formValue(data, "__proto__.scope"), "local");
});

async function fixture(check) {
  const window = new Window({ url: "http://forms.test/" });
  for (const key of ["window", "document", "Node", "Element", "HTMLElement", "HTMLInputElement", "HTMLFormElement", "HTMLButtonElement", "HTMLSelectElement", "HTMLTextAreaElement", "Event", "CustomEvent", "MutationObserver"]) globalThis[key] = window[key];
  document.body.innerHTML = `<form><div data-bml-schema-form><input type="hidden" data-bml-ref="value" name="attributes" /><p data-bml-ref="feedback" hidden></p><div data-schema-property="summary" data-schema-control="text-input"><input id="summary" /><p id="summary-error" data-schema-error hidden></p></div><div data-schema-property="active" data-schema-control="checkbox"><input type="checkbox" /><p data-schema-error hidden></p></div><button type="submit">Submit</button></div></form>`;
  const root = document.querySelector("[data-bml-schema-form]"), form = root.closest("form");
  const schema = { type: "object", properties: { summary: { type: "string", minLength: 3 }, active: { type: "boolean" } }, required: ["summary"] };
  root.dataset.schema = JSON.stringify(schema); root.dataset.value = JSON.stringify({ hidden: "retain", active: false });
  for (const field of root.querySelectorAll("[data-schema-property]")) field.dataset.propertySchema = JSON.stringify(schema.properties[field.dataset.schemaProperty]);
  const cleanup = [], updates = [];
  const ctx = { root, ref: name => root.querySelector(`[data-bml-ref="${name}"]`), onUpdate: callback => updates.push(callback), onUnmount: callback => cleanup.push(callback), on: (target, type, listener, options) => { target.addEventListener(type, listener, options); cleanup.push(() => target.removeEventListener(type, listener, options)); } };
  try { api.mountSchemaForm(ctx); await check({ root, form, update: () => updates.forEach(callback => callback()), unmount: () => cleanup.splice(0).forEach(callback => callback()), settle: () => new Promise(resolve => setTimeout(resolve, 0)) }); }
  finally { cleanup.forEach(callback => callback()); await window.happyDOM.abort(); }
}

test("native values block invalid submission, preserve hidden data and remain editable after failure", () => fixture(async ({ root, form, update, settle }) => {
  const summary = root.querySelector("#summary"), active = root.querySelector('[type="checkbox"]');
  const submit = () => { const event = new Event("submit", { bubbles: true, cancelable: true }); form.dispatchEvent(event); return event; };
  assert.equal(submit().defaultPrevented, true);
  assert.equal(document.activeElement, summary);
  summary.value = "Draft task"; summary.dispatchEvent(new Event("input", { bubbles: true }));
  active.checked = true; active.dispatchEvent(new Event("change", { bubbles: true }));
  assert.equal(submit().defaultPrevented, false);
  assert.deepEqual(JSON.parse(root.querySelector('[name="attributes"]').value), { hidden: "retain", active: true, summary: "Draft task" });
  form.setAttribute("data-bml-action-pending", ""); await settle(); assert.equal(root.inert, true);
  form.removeAttribute("data-bml-action-pending"); await settle(); assert.equal(root.inert, false);
  update(); assert.equal(summary.value, "Draft task", "unchanged server state preserves a failed-action draft");
  summary.value = "Edited draft"; summary.dispatchEvent(new Event("input", { bubbles: true }));
  assert.equal(JSON.parse(root.querySelector('[name="attributes"]').value).summary, "Edited draft");
}));

test("unsupported controls block submission and unmount removes form-level handlers", () => fixture(async ({ root, form, unmount }) => {
  root.insertAdjacentHTML("beforeend", '<p data-schema-unsupported>Unknown control: custom-upload</p>');
  let submitted = 0; form.addEventListener("submit", () => submitted++);
  const event = () => new Event("submit", { bubbles: true, cancelable: true });
  form.dispatchEvent(event()); assert.equal(submitted, 0);
  assert.match(root.querySelector('[data-bml-ref="feedback"]').textContent, /custom-upload/);
  unmount(); form.dispatchEvent(event()); assert.equal(submitted, 1);
}));

test("searching a shared select does not publish its query as a form value", () => fixture(async ({ root }) => {
  root.insertAdjacentHTML("beforeend", '<div data-schema-property="owner" data-schema-control="select" data-property-schema="{}"><div data-bml-design-select><select><option value="one">First</option><option value="two">Second</option></select><input type="search" /></div><p data-schema-error hidden></p></div>');
  const query = root.querySelector('[type="search"]'), selection = root.querySelector('select');
  query.value = 'Second'; query.dispatchEvent(new Event('input', { bubbles: true }));
  assert.equal(JSON.parse(root.querySelector('[name="attributes"]').value).owner, undefined);
  selection.value = 'two'; selection.dispatchEvent(new Event('change', { bubbles: true }));
  assert.equal(JSON.parse(root.querySelector('[name="attributes"]').value).owner, 'two');
}));
