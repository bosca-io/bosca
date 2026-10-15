import assert from "node:assert/strict";
import { writeFile } from "node:fs/promises";

const base = process.env.GIT_UI_BASE_URL;
if (!base) throw Error("Set GIT_UI_BASE_URL to the isolated browser fixture.");
const targets = await (
  await fetch(process.env.GIT_UI_CDP_URL + "/json")
).json();
const ws = new WebSocket(
  targets.find((t) => t.url.startsWith(base)).webSocketDebuggerUrl,
);
await new Promise((resolve) =>
  ws.addEventListener("open", resolve, { once: true }),
);
let nextId = 0;
const pending = new Map(),
  errors = [],
  requests = [];
ws.addEventListener("message", (event) => {
  const message = JSON.parse(event.data);
  if (message.method === "Runtime.exceptionThrown")
    errors.push(message.params.exceptionDetails);
  if (message.method === "Network.requestWillBeSent") {
    const request = message.params.request;
    if (request.url.endsWith("/_bml/action") && request.postData) {
      const data = JSON.parse(request.postData);
      if (data.page === "/search") requests.push(data);
    }
  }
  if (message.id) {
    const task = pending.get(message.id);
    pending.delete(message.id);
    message.error ? task.reject(message.error) : task.resolve(message.result);
  }
});
const call = (method, params = {}) =>
  new Promise((resolve, reject) => {
    const id = ++nextId;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });
async function ev(fn, ...args) {
  const expression =
    typeof fn === "string"
      ? fn
      : "(" + fn.toString() + ")(..." + JSON.stringify(args) + ")";
  const result = await call("Runtime.evaluate", {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  if (result.exceptionDetails)
    throw Error(JSON.stringify(result.exceptionDetails));
  return result.result.value;
}
async function until(fn, ...args) {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (
      await ev(
        "Boolean((" + fn.toString() + ")(..." + JSON.stringify(args) + "))",
      )
    )
      return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw Error("Timeout " + fn);
}
async function nav(path) {
  await call("Page.navigate", { url: base + path });
  await until(
    (url) =>
      location.href === url &&
      document.readyState === "complete" &&
      !document.querySelector("[data-bml-ref=signout]")?.disabled &&
      document.querySelector(".search-workspace [name=q]"),
    base + path,
  );
  await ev("document.fonts.ready");
  await until(() =>
    document.querySelector("select[name=repo]")?.classList.contains("enhanced"),
  );
}
async function typeQuery(text, submit = false) {
  await ev(
    (text, submit) => {
      const query = document.querySelector(".search-workspace [name=q]");
      query.focus();
      query.value = text;
      query.setSelectionRange(text.length, text.length);
      query.dispatchEvent(new Event("input", { bubbles: true }));
      if (submit) query.form.requestSubmit();
    },
    text,
    submit,
  );
}
async function settled(query) {
  await until(
    (query) =>
      document.querySelector("[data-search-query]")?.dataset.searchQuery ===
        query &&
      document.querySelector("[data-bml-ref=loading]").hidden &&
      !document.querySelector(".search-results").hidden,
    query,
  );
}
async function key(key, code) {
  for (const type of ["keyDown", "keyUp"])
    await call("Input.dispatchKeyEvent", {
      type,
      key,
      code: key,
      windowsVirtualKeyCode: code,
    });
}
async function select(name, value) {
  await ev(
    (name, value) => {
      const select = document.querySelector(
        ".search-workspace select[name=" + name + "]",
      );
      select.value = value;
      select.dispatchEvent(new Event("change", { bubbles: true }));
    },
    name,
    value,
  );
}
async function capture(name) {
  const shot = await call("Page.captureScreenshot", {
    format: "png",
    captureBeyondViewport: false,
  });
  await writeFile(
    process.env.GIT_UI_ARTIFACT_DIR + "/bosca-git-ui-search-" + name + ".png",
    Buffer.from(shot.data, "base64"),
  );
}
await call("Runtime.enable");
await call("Page.enable");
await call("Network.enable");
await call("Emulation.setDeviceMetricsOverride", {
  width: 1440,
  height: 900,
  deviceScaleFactor: 1,
  mobile: false,
});
await nav("/search");
assert.equal(
  await ev(() => document.querySelector("[name=repo]").value),
  "repo",
);
assert.equal(
  await ev(() => document.activeElement?.name),
  "q",
  "Search focuses its query",
);
assert.equal(
  await ev(() =>
    Math.round(
      document.querySelector(".search-input").getBoundingClientRect().height,
    ),
  ),
  40,
);
assert.equal(
  await ev(() => document.querySelector("[name=ref]").value),
  "main",
);
assert.ok(
  await ev(() => document.body.innerText.includes("Search this repository")),
);
const originalRequests = requests.length;
await typeQuery("snippet");
await new Promise((resolve) => setTimeout(resolve, 150));
assert.equal(
  requests.length,
  originalRequests,
  "Typing waits for the debounce",
);
await settled("snippet");
assert.equal(
  requests.length,
  originalRequests + 1,
  "A query produces one action",
);
assert.equal(
  await ev(() => document.activeElement?.name),
  "q",
  "Live results preserve focus",
);
assert.equal(
  await ev(() => document.activeElement?.selectionStart),
  7,
  "Live results preserve caret",
);
assert.equal(
  await ev(() => document.querySelector(".result-snippet").textContent),
  "<script>snippet</script>",
);
assert.equal(
  await ev(() => document.querySelector(".result-snippet mark").textContent),
  "snippet",
);
assert.equal(
  await ev(() => document.querySelector(".result-snippet script")),
  null,
  "Snippets are safe text",
);
assert.equal(
  await ev(() => new URLSearchParams(location.search).get("q")),
  "snippet",
);
await capture("content");

// Slow responses may update server state, but must not display obsolete results.
await ev(() => {
  window.searchUpdates = [];
  document.addEventListener("bml:update", () => {
    const state = document.querySelector("[data-search-query]");
    if (state)
      window.searchUpdates.push({
        query: state.dataset.searchQuery,
        visible: !document.querySelector(".search-results").hidden,
      });
  });
});
const beforeSlow = requests.length;
await typeQuery("slow", true);
await until(() =>
  document.querySelector("[data-bml-ref=loading]").checkVisibility(),
);
await typeQuery("obsolete", true);
await typeQuery("snippet", true);
await settled("snippet");
assert.deepEqual(
  requests.slice(beforeSlow).map((r) => r.args[1]),
  ["slow", "snippet"],
  "Queued searches coalesce to the latest query",
);
assert.ok(
  await ev(() =>
    window.searchUpdates
      .filter((update) => update.query === "slow")
      .every((update) => !update.visible),
  ),
  "Old results stay hidden",
);
assert.equal(await ev(() => document.activeElement?.name), "q");
assert.equal(
  await ev(() => document.querySelector("[name=q]").value),
  "snippet",
);

await select("ref", "feature/one");
await until(
  () => new URLSearchParams(location.search).get("ref") === "feature/one",
);
assert.ok(
  await ev(() =>
    document.querySelector(".result-row").href.includes("ref=feature%2Fone"),
  ),
);
await ev(() =>
  document.querySelector("[role=radio][data-value=content]").focus(),
);
await key("ArrowRight", 39);
await until(() => new URLSearchParams(location.search).get("mode") === "paths");
assert.equal(
  await ev(
    () =>
      document.querySelector("[role=radio][aria-checked=true]").dataset.value,
  ),
  "paths",
);
assert.equal(
  await ev(() => document.querySelector("[name=q]").placeholder),
  "Search file paths…",
);
await typeQuery("paths", true);
await settled("paths");
await select("ref", "refs/tags/v1");
await until(
  () => new URLSearchParams(location.search).get("ref") === "refs/tags/v1",
);
assert.equal(await ev(() => document.querySelectorAll(".path-row").length), 3);
assert.ok(
  await ev(() =>
    [...document.querySelectorAll(".result-size")].some(
      (e) => e.textContent === "1.5 KB",
    ),
  ),
);
assert.ok(
  await ev(() =>
    document
      .querySelector('[aria-label="Open docs"]')
      .href.endsWith("ref=abc&path=docs"),
  ),
);
assert.ok(
  await ev(() =>
    document
      .querySelector('[aria-label="Open README.md"]')
      .href.includes("file=README.md"),
  ),
);
assert.ok(await ev(() => document.body.innerText.includes("Symbolic link")));
await capture("paths");
const storedUrl = await ev(() => location.pathname + location.search);
await nav(storedUrl);
assert.equal(await ev(() => document.querySelector("[name=q]").value), "paths");
assert.equal(
  await ev(() => document.querySelector("[name=mode]").value),
  "paths",
);
assert.equal(
  await ev(() => document.querySelector("[name=ref]").value),
  "refs/tags/v1",
);
await ev(() =>
  document.querySelector("[role=radio][data-value=paths]").focus(),
);
await key("ArrowLeft", 37);
await until(
  () => new URLSearchParams(location.search).get("mode") === "content",
);
assert.equal(
  await ev(() => document.querySelector("[name=ref]").value),
  "main",
  "Content mode resets a tag to a branch",
);
assert.equal(await ev(() => document.querySelector("[name=q]").value), "paths");
await select("ref", "feature/one");
await until(
  () => new URLSearchParams(location.search).get("ref") === "feature/one",
);
await select("repo", "empty");
await until(() => new URLSearchParams(location.search).get("repo") === "empty");
assert.equal(
  await ev(() => document.querySelector("[name=ref]").value),
  "main",
  "Changing repository resets the ref",
);
assert.equal(
  await ev(() => document.querySelector("[name=q]").value),
  "paths",
  "Changing repository retains the query",
);
assert.ok(await ev(() => document.body.innerText.includes("no commits yet")));
await select("repo", "repo");
await until(() => new URLSearchParams(location.search).get("repo") === "repo");
await typeQuery("nomatches", true);
await settled("nomatches");
assert.ok(await ev(() => document.body.innerText.includes("No matches")));
await typeQuery("fail", true);
await settled("fail");
assert.ok(
  await ev(() =>
    document
      .querySelector("[role=alert]")
      .textContent.includes("Search unavailable"),
  ),
);
assert.equal(await ev(() => document.querySelector("[name=q]").value), "fail");
await capture("error");

// Transport failures must be visible and retryable without losing the query.
await call("Network.setBlockedURLs", { urls: [base + "/_bml/action"] });
await typeQuery("snippet", true);
await until(() =>
  document.querySelector("[data-bml-ref=transportError]").checkVisibility(),
);
assert.equal(
  await ev(() => document.querySelector("[name=q]").value),
  "snippet",
);
await call("Network.setBlockedURLs", { urls: [] });
await ev(() =>
  document.querySelector(".search-workspace form").requestSubmit(),
);
await settled("snippet");
assert.ok(
  await ev(
    () =>
      !document
        .querySelector("[data-bml-ref=transportError]")
        .checkVisibility(),
  ),
);
await ev(() => document.querySelector('[aria-label="Clear search"]').click());
await settled("");
assert.ok(
  await ev(() => document.body.innerText.includes("Search this repository")),
);
assert.equal(await ev(() => document.querySelector(".result-row")), null);
assert.equal(await ev(() => document.activeElement?.name), "q");

// Enter submits immediately and opens the matching Markdown source line.
await typeQuery("lines");
await key("Enter", 13);
await settled("lines");
await ev(() => document.querySelector(".result-row").focus());
await key("Enter", 13);
await until(
  () =>
    location.pathname === "/repositories/repo" &&
    document.querySelector(".cm-line"),
);
assert.equal(
  await ev(() => new URLSearchParams(location.search).get("line")),
  "2",
);
assert.equal(
  await ev(() =>
    document.querySelector("[data-tab=Source]").getAttribute("aria-selected"),
  ),
  "true",
);
assert.ok(
  await ev(
    () => !document.querySelector("[data-bml-ref=sourceSurface]").hidden,
  ),
);
assert.equal(
  await ev(() => document.querySelector(".cm-activeLine")?.textContent),
  "matching line",
);
await capture("line");
assert.deepEqual(errors, [], "Search raised browser exceptions");
const report = {
  checks: [
    "debounce",
    "focus and caret",
    "safe highlighted snippets",
    "latest request coalescing",
    "ref and repository changes",
    "keyboard mode control",
    "URL restoration",
    "path metadata",
    "visible API and transport errors",
    "retry",
    "clear",
    "Enter submit",
    "Markdown source line navigation",
  ],
  actions: requests.length,
  errors,
};
await writeFile(
  process.env.GIT_UI_ARTIFACT_DIR + "/bosca-git-ui-search-checks.json",
  JSON.stringify(report, null, 2),
);
console.log(JSON.stringify({ search: "passed", ...report }));
ws.close();
