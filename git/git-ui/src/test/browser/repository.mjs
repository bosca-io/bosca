import { writeFile } from "node:fs/promises";
import assert from "node:assert/strict";
const base = process.env.GIT_UI_BASE_URL;
if (!base) throw Error("Set GIT_UI_BASE_URL to the isolated browser fixture.");
const port = new URL(base).port;
const targets = await (
  await fetch(`${process.env.GIT_UI_CDP_URL}/json`)
).json();
const ws = new WebSocket(
  targets.find((t) => t.type === "page").webSocketDebuggerUrl,
);
await new Promise((r) => ws.addEventListener("open", r, { once: true }));
let n = 0;
const pending = new Map(),
  errors = [],
  treeActions = [];
ws.addEventListener("message", (e) => {
  const m = JSON.parse(e.data);
  if (m.method === "Runtime.exceptionThrown")
    errors.push(m.params.exceptionDetails);
  if (m.method === "Network.requestWillBeSent" && m.params.request.postData) {
    const request = m.params.request;
    if (request.url.endsWith("/_bml/action")) {
      const data = JSON.parse(request.postData);
      if (data.stateKey?.startsWith("git-sidebar-tree.model"))
        treeActions.push({ method: data.method, args: data.args });
    }
  }
  if (m.method === "Page.javascriptDialogOpening")
    void call("Page.handleJavaScriptDialog", { accept: true });
  if (m.id) {
    const p = pending.get(m.id);
    pending.delete(m.id);
    m.error ? p.reject(m.error) : p.resolve(m.result);
  }
});
const call = (method, params = {}) =>
  new Promise((resolve, reject) => {
    const id = ++n;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });
async function ev(expression) {
  const r = await call("Runtime.evaluate", {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  if (r.exceptionDetails) throw Error(JSON.stringify(r.exceptionDetails));
  return r.result.value;
}
async function until(s) {
  for (let i = 0; i < 100; i++) {
    if (await ev(s)) return;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw Error("Timeout " + s);
}
await call("Runtime.enable");
await call("Page.enable");
await call("Network.enable");
// Focus events require an active page in the isolated headless browser.
await call("Page.bringToFront");
await call("Network.setCookie", {
  name: "_git_test",
  value: "ui-test-token",
  url: `http://localhost:${port}`,
});
await call("Emulation.setDeviceMetricsOverride", {
  width: 1440,
  height: 900,
  deviceScaleFactor: 1,
  mobile: false,
});
// Navigate through actual file rows, including the deferred tree's action boundary.
await call("Page.navigate", { url: base + "/repositories/repo" });
await until(
  'document.querySelector(".repository-workspace [data-bml-deferred]")?.dataset.bmlDeferredState === "loaded" && document.querySelector("a.ui-tree-row[aria-label=\\"README.md\\"]")',
);
const readmeRow = await ev(`(() => {
  const link = document.querySelector('a.ui-tree-row[aria-label="README.md"]');
  return {link:link.outerHTML, row:link.closest('[role=treeitem]').outerHTML};
})()`);
const readmePoint = await ev(`(() => {
  const bounds = document.querySelector('a.ui-tree-row[aria-label="README.md"]').getBoundingClientRect();
  return {x:bounds.left+bounds.width/2,y:bounds.top+bounds.height/2};
})()`);
for (const type of ["mousePressed", "mouseReleased"])
  await call("Input.dispatchMouseEvent", {
    type,
    ...readmePoint,
    button: "left",
    clickCount: 1,
  });
await until(
  'new URLSearchParams(location.search).get("file") === "README.md" || document.body.innerText.includes("Folder is no longer available")',
);
const readmeNavigation = await ev(
  `({url:location.href, folderError:document.body.innerText.includes('Folder is no longer available')})`,
);
await writeFile(
  process.env.GIT_UI_ARTIFACT_DIR + "/bosca-git-ui-file-navigation.json",
  JSON.stringify({ readmeRow, treeActions, readmeNavigation }, null, 2),
);
await writeFile(
  process.env.GIT_UI_ARTIFACT_DIR + "/bosca-git-ui-readme-navigation.png",
  Buffer.from(
    (
      await call("Page.captureScreenshot", {
        format: "png",
        captureBeyondViewport: false,
      })
    ).data,
    "base64",
  ),
);
assert.equal(
  readmeNavigation.folderError,
  false,
  "Clicking README must not dispatch folder expansion",
);
assert.equal(
  new URL(readmeNavigation.url).searchParams.get("file"),
  "README.md",
  "A real pointer click opens the README",
);
assert.deepEqual(treeActions, [], "File links never invoke the folder action");
await until(
  'document.querySelector(".repository-workspace [data-bml-deferred]")?.dataset.bmlDeferredState === "loaded" && document.querySelector(".markdown-file")',
);
await ev(
  'document.querySelector("button.ui-tree-row[aria-label=\\"Expand docs\\"]").click()',
);
await until(
  'document.querySelector("a.ui-tree-row[aria-label=\\"docs/guide.md\\"]")',
);
assert.deepEqual(
  treeActions,
  [{ method: "expand", args: ["docs"] }],
  "Only the folder uses its server action",
);
await ev(
  'document.querySelector("a.ui-tree-row[aria-label=\\"docs/guide.md\\"]").focus()',
);
for (const type of ["keyDown", "keyUp"])
  await call("Input.dispatchKeyEvent", {
    type,
    key: "Enter",
    code: "Enter",
    windowsVirtualKeyCode: 13,
  });
await until(
  'new URLSearchParams(location.search).get("file") === "docs/guide.md" && document.querySelector(".repository-workspace [data-bml-deferred]")?.dataset.bmlDeferredState === "loaded"',
);
assert.equal(
  await ev(
    'document.querySelector("[role=treeitem][data-path=\\"docs/guide.md\\"]").getAttribute("aria-selected")',
  ),
  "true",
);
assert.deepEqual(
  treeActions,
  [{ method: "expand", args: ["docs"] }],
  "Keyboard file navigation never expands a file",
);
assert.ok(
  !(await ev(
    'document.body.innerText.includes("Folder is no longer available")',
  )),
);
await call("Page.navigate", { url: `http://localhost:${port}/repositories` });
await until(
  'document.querySelector(".owner-toolbar .select-query")?.hasAttribute("aria-controls")',
);
assert.match(await ev("document.body.innerText"), /Active project/);
assert.doesNotMatch(await ev("document.body.innerText"), /Archived project/);
const width = await ev(
  'document.querySelector(".owner-toolbar .owner-picker").getBoundingClientRect().width',
);
await ev('document.querySelector(".owner-toolbar .select-trigger").click()');
assert.match(
  await ev(
    'document.querySelector(".owner-toolbar .select-options").innerText',
  ),
  /All accessible repositories/,
);
await ev(
  'document.querySelector(".owner-toolbar .select-query").focus();document.querySelector(".owner-toolbar .select-query").value="Alice";document.querySelector(".owner-toolbar .select-query").dispatchEvent(new Event("input",{bubbles:true}))',
);
assert.ok(
  await ev(
    'document.querySelector(".owner-toolbar .select-popup").getAttribute("aria-busy") === "true"',
  ),
  "Async profile search reports loading",
);
await until(
  'document.querySelectorAll(".owner-toolbar .select-options li").length>=2',
);
assert.equal(
  await ev(
    'document.querySelector(".owner-toolbar .owner-picker").getBoundingClientRect().width',
  ),
  width,
);
assert.match(
  await ev(
    'document.querySelector(".owner-toolbar .select-options").innerText',
  ),
  /Alice · @alice-2/,
);
for (const [query, message] of [
  ["Nobody", "No options found."],
  ["Fail", "Search failed. Please try again."],
]) {
  await ev(
    `document.querySelector('.owner-toolbar .select-query').value=${JSON.stringify(query)};document.querySelector('.owner-toolbar .select-query').dispatchEvent(new Event('input',{bubbles:true}))`,
  );
  await until(
    `document.querySelector('.owner-toolbar .select-status').textContent === ${JSON.stringify(message)}`,
  );
  assert.equal(
    await ev(
      'document.querySelector(".owner-toolbar .select-trigger").getAttribute("aria-expanded")',
    ),
    "true",
    "Search feedback stays available for retry",
  );
}
await ev(
  'document.querySelector(".owner-toolbar .select-query").value="Alice";document.querySelector(".owner-toolbar .select-query").dispatchEvent(new Event("input",{bubbles:true}))',
);
await until(
  'document.querySelectorAll(".owner-toolbar .select-options li").length>=2',
);
await ev(
  'document.querySelector(".owner-toolbar .select-options li").dispatchEvent(new PointerEvent("pointerdown",{bubbles:true}))',
);
await until(
  'new URLSearchParams(location.search).get("owner") && document.readyState === "complete" && document.querySelector(".owner-toolbar .select-query")',
);
assert.ok(
  await ev('new URLSearchParams(location.search).get("owner")'),
  "Owner selection applies without another submit button",
);
const create = '[data-bml-component="git-repository-create"]';
await until(`document.querySelector('${create} [data-bml-ref=trigger]')`);
await ev(
  `document.querySelector('${create} [data-bml-ref=trigger]').click(); document.querySelector('${create} input[name=name]').value='My New Project'; document.querySelector('${create} input[name=name]').dispatchEvent(new Event('input',{bubbles:true}))`,
);
assert.equal(
  await ev(`document.querySelector('${create} input[name=slug]').value`),
  "my-new-project",
  "Repository name suggests its slug",
);
assert.ok(
  await ev(`document.querySelector('${create} input[name=readme]').checked`),
  "Creation matches Studio's README initialization default",
);
await ev(
  `document.querySelector('${create} input[name=slug]').value='my-custom-slug'; document.querySelector('${create} input[name=name]').value='Renamed Project'; document.querySelector('${create} input[name=name]').dispatchEvent(new Event('input',{bubbles:true}))`,
);
assert.equal(
  await ev(`document.querySelector('${create} input[name=slug]').value`),
  "my-custom-slug",
  "Editing a name preserves an explicitly chosen slug",
);
await ev(
  ` document.querySelector('${create} input[name=name]').value='Rejected'; document.querySelector('${create} input[name=slug]').value='rejected'; document.querySelector('${create} input[name=description]').value='Keep my description'; document.querySelector('${create} select[name=ownerId]').value='owner'; document.querySelector('${create} button[type=submit]').click()`,
);
await until(
  `document.querySelector('${create} [role=alert]')?.textContent.includes('Slug unavailable') && document.querySelector('${create} dialog').open`,
);
assert.equal(
  await ev(`document.querySelector('${create} input[name=name]').value`),
  "Rejected",
);
assert.equal(
  await ev(`document.querySelector('${create} input[name=description]').value`),
  "Keep my description",
);
assert.ok(
  await ev(`document.querySelector('${create} dialog').matches(':modal')`),
  "Failed creation remains a real modal after the island patch",
);
await ev(`document.querySelector('${create} [data-bml-ref=cancel]').click()`);
await call("Page.navigate", {
  url: `http://localhost:${port}/repositories/repo?file=docs%2Fa%20%23%20b.txt`,
});
await until('document.querySelector(".cm-editor")');
assert.equal(
  await ev('document.querySelector(".cm-content").textContent'),
  "<script>source</script>",
);
assert.equal(await ev('document.querySelector(".cm-content").tabIndex'), 0);
await call("Browser.grantPermissions", {
  permissions: ["clipboardReadWrite", "clipboardSanitizedWrite"],
  origin: base,
});
await ev(
  'document.querySelector(".repository-controls summary[aria-label=\\"Clone repository\\"]").click()',
);
await until(
  'document.querySelector(".popover-content").matches(":popover-open")',
);
assert.equal(
  await ev('document.activeElement?.getAttribute("aria-label")'),
  "Clone URL",
);
assert.ok(
  await ev(
    "document.activeElement.selectionStart === 0 && document.activeElement.selectionEnd === document.activeElement.value.length",
  ),
  "The clone URL selects on focus",
);
const copyBounds = await ev(`(() => {
  const input = document.querySelector('.copy-field input').getBoundingClientRect();
  const button = document.querySelector('.copy-field button').getBoundingClientRect();
  return {width:input.width, y:input.top, buttonY:button.top, x:button.left+button.width/2, pointY:button.top+button.height/2};
})()`);
assert.ok(
  Math.abs(copyBounds.y - copyBounds.buttonY) < 1,
  "Clone URL and copy action align",
);
for (const type of ["mousePressed", "mouseReleased"])
  await call("Input.dispatchMouseEvent", {
    type,
    x: copyBounds.x,
    y: copyBounds.pointY,
    button: "left",
    clickCount: 1,
  });
await until(
  'document.querySelector(".copy-control [role=status]").textContent',
);
assert.match(
  await ev('document.querySelector(".copy-control [role=status]").textContent'),
  /Copied|Select and copy/,
);
assert.equal(
  await ev("navigator.clipboard.readText()"),
  "https://git.example.test/owner/repo.git",
  "Copy writes the actual clone URL",
);
assert.ok(
  await ev(
    '!document.querySelector(".copy-field [data-bml-ref=doneIcon]").hidden',
  ),
  "Copy success shows its check icon",
);
assert.equal(
  await ev(
    'document.querySelector(".copy-field input").getBoundingClientRect().width',
  ),
  copyBounds.width,
  "Copy feedback preserves input width",
);
await ev(`(() => {
  window.originalClipboardWrite = navigator.clipboard.writeText;
  navigator.clipboard.writeText = async () => { throw new DOMException("Denied", "NotAllowedError"); };
  document.querySelector('.copy-field button').click();
})()`);
await until(
  '!document.querySelector(".copy-field [data-bml-ref=error]").hidden',
);
assert.ok(
  await ev(
    'document.activeElement?.getAttribute("aria-label") === "Clone URL" && document.activeElement.selectionEnd === document.activeElement.value.length',
  ),
  "Clipboard failure selects the clone URL for manual copying",
);
await ev(
  "navigator.clipboard.writeText = window.originalClipboardWrite; delete window.originalClipboardWrite",
);
await ev(
  'document.dispatchEvent(new KeyboardEvent("keydown", {key:"Escape",bubbles:true}))',
);
await until(
  '!document.querySelector(".popover-content").matches(":popover-open")',
);

const geometry = await ev(
  '({surface:document.querySelector(".file-surface").getBoundingClientRect().height,editor:document.querySelector(".cm-editor").getBoundingClientRect().height,radius:getComputedStyle(document.querySelector(".cm-editor")).borderRadius,overflow:document.documentElement.scrollWidth>innerWidth})',
);
assert.ok(geometry.editor > 300, JSON.stringify(geometry));
assert.equal(geometry.radius, "0px");
assert.equal(
  await ev(
    'getComputedStyle(document.querySelector(".cm-editor")).backgroundColor',
  ),
  "rgb(16, 19, 28)",
  "CodeMirror must use the same surface palette as the Git UI",
);
await call("Page.navigate", {
  url: `http://localhost:${port}/repositories/repo?file=docs%2Fa.txt&edit=true`,
});
await until(
  'document.querySelector(".cm-editor") && document.querySelector("form.code-widget")',
);
await ev(
  'document.querySelector("form.code-widget [name=path]").value="protected.txt";document.querySelector("[name=message]").value="Keep my commit";document.querySelector(".cm-content").focus()',
);
await call("Input.insertText", { text: "draft edit\n" });
await ev(
  'document.querySelector("form.code-widget button[type=submit]").click()',
);
await until('document.body.innerText.includes("Branch is protected")');
assert.ok(
  await ev('document.querySelector(".cm-editor") !== null'),
  "CodeMirror disappeared after failed commit",
);
assert.match(
  await ev('document.querySelector(".cm-content").textContent'),
  /draft edit/,
);
assert.equal(
  await ev('document.querySelector("[name=message]").value'),
  "Keep my commit",
);
const createdPath = `browser-${Date.now()}.txt`;
await ev(
  `document.querySelector("form.code-widget [name=path]").value=${JSON.stringify(createdPath)};document.querySelector("form.code-widget button[type=submit]").click()`,
);
await until(
  `location.search.includes(${JSON.stringify("file=" + createdPath)}) && document.querySelector(".cm-editor")`,
);
await call("Emulation.setDeviceMetricsOverride", {
  width: 390,
  height: 844,
  deviceScaleFactor: 1,
  mobile: true,
});
assert.equal(
  await ev("document.documentElement.scrollWidth>innerWidth"),
  false,
);
const layouts = [];
for (const width of [1440, 1024]) {
  await call("Emulation.setDeviceMetricsOverride", {
    width,
    height: 900,
    deviceScaleFactor: 1,
    mobile: false,
  });
  for (const file of ["empty.txt", "long.txt"]) {
    await call("Page.navigate", {
      url: base + "/repositories/repo?file=" + file,
    });
    await until(
      'document.readyState==="complete" && document.querySelector(".cm-editor")',
    );
    const size = await ev(
      '({height:document.querySelector(".cm-editor").getBoundingClientRect().height,surface:document.querySelector(".file-surface").getBoundingClientRect().height,gutters:document.querySelector(".cm-lineNumbers")!==null,scroll:document.querySelector(".cm-scroller").scrollHeight>document.querySelector(".cm-scroller").clientHeight,radius:getComputedStyle(document.querySelector(".cm-editor")).borderRadius})',
    );
    assert.ok(size.height >= 900 * 0.65, JSON.stringify(size));
    assert.equal(size.gutters, true);
    assert.equal(size.radius, "0px");
    if (file === "long.txt") assert.equal(size.scroll, true);
    layouts.push({ width, file, ...size });
  }
}
await writeFile(
  `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-code-desktop.png`,
  Buffer.from(
    (await call("Page.captureScreenshot", { format: "png" })).data,
    "base64",
  ),
);
await call("Page.navigate", {
  url: base + "/repositories/repo?file=README.md",
});
await until('document.querySelector(".markdown img")?.complete');
assert.ok(
  await ev('document.querySelector(".markdown img").naturalWidth>0'),
  "Markdown image failed to load",
);
await writeFile(
  `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-markdown.png`,
  Buffer.from(
    (await call("Page.captureScreenshot", { format: "png" })).data,
    "base64",
  ),
);
await ev(
  'document.querySelector(".markdown img").src="/owner/repo/raw/abc/missing.png"',
);
await until('document.body.innerText.includes("Image unavailable: Logo")');
assert.ok(await ev('document.body.innerText.includes("Hello")'));
await call("Emulation.setDeviceMetricsOverride", {
  width: 390,
  height: 844,
  deviceScaleFactor: 1,
  mobile: true,
});
await call("Page.navigate", { url: base + "/repositories/repo?file=long.txt" });
await until(
  'document.querySelector(".cm-editor") && document.readyState==="complete"',
);
assert.equal(await ev("innerWidth"), 390);
assert.equal(await ev("document.documentElement.scrollWidth>390"), false);
assert.ok(
  await ev(
    'document.querySelector(".cm-editor").getBoundingClientRect().height>=240',
  ),
);
assert.ok(
  await ev(
    'document.querySelector(".file-surface").getBoundingClientRect().height>=360',
  ),
);
assert.ok(
  await ev(
    'document.querySelector(".file-surface").getBoundingClientRect().height<=innerHeight',
  ),
  "Mobile file surface grows with line count",
);
assert.ok(
  await ev(
    'document.querySelector(".cm-scroller").scrollHeight>document.querySelector(".cm-scroller").clientHeight',
  ),
  "Mobile long file must scroll inside CodeMirror",
);
assert.deepEqual(errors, [], "Repository pages raised browser exceptions");
console.log(
  JSON.stringify({
    geometry,
    layouts,
    errors,
    checks: [
      "stable distinct owner picker",
      "owner All choice",
      "automatic owner filtering and async recovery",
      "creation defaults and editable slug suggestion",
      "failed creation preserves its modal and draft",
      "clone copy feedback",
      "pointer README file navigation",
      "keyboard nested-file navigation",
      "folder actions exclude file links",
      "selected clone URL and actual clipboard value",
      "stable copy success icon",
      "clipboard failure selects manual-copy fallback",
      "numbered viewer",
      "full height CodeMirror",
      "failed commit keeps editor and draft",
      "successful commit navigates",
      "mobile fit",
      "empty and long file layout",
      "authenticated fixture Markdown image",
      "missing image preserves prose",
    ],
  }),
);
await ev(
  'document.querySelector(".cm-editor").scrollIntoView({block:"center"})',
);
assert.ok(
  await ev(
    'Math.min(innerHeight,document.querySelector(".cm-editor").getBoundingClientRect().bottom)-Math.max(0,document.querySelector(".cm-editor").getBoundingClientRect().top)>=240',
  ),
  "Mobile code area is clipped",
);
await writeFile(
  `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-code.png`,
  Buffer.from(
    (await call("Page.captureScreenshot", { format: "png" })).data,
    "base64",
  ),
);
await call("Emulation.setDeviceMetricsOverride", {
  width: 1440,
  height: 900,
  deviceScaleFactor: 1,
  mobile: false,
});
await call("Network.setCookie", {
  name: "_git_test",
  value: "bootstrap-token",
  url: base,
});
async function cleanupReadme() {
  // Restore only the scratch README created by this fixture, through the same-origin API.
  await ev(`(async () => {
    const response = await fetch('/graphql', {
      method: 'POST', headers: {'Content-Type':'application/json'},
      body: JSON.stringify({
        operationName: 'GitUiDeleteFile',
        query: 'mutation GitUiDeleteFile($id:UUID!, $branch:String!, $path:String!, $name:String!, $email:String!) { git { deleteFile(repositoryId:$id, branch:$branch, path:$path, authorName:$name, authorEmail:$email) { commitSha } } }',
        variables: {id:'empty', branch:'main', path:'README.md', name:'admin', email:'studio@bosca.io'}
      })
    });
    const result = await response.json();
    if (!response.ok || result.errors) throw Error(JSON.stringify(result.errors ?? response.status));
  })()`);
}
await cleanupReadme();
await call("Page.navigate", { url: base + "/repositories/empty" });
await until('document.body.innerText.includes("Start this repository")');
await ev(
  'Array.from(document.querySelectorAll("a")).find(a=>a.textContent.trim()==="Create first file").click()',
);
await until('!!document.querySelector("form.code-widget .cm-content")');
assert.match(
  await ev('document.querySelector(".commit-controls").innerText'),
  /admin\s*<\s*studio@bosca\.io\s*>/,
  "Commit preview uses the same fallback as file actions",
);
await ev(
  'document.querySelector("form.code-widget [name=path]").value="README.md";document.querySelector("[name=message]").value="Add README";document.querySelector(".cm-content").focus()',
);
await call("Input.insertText", {
  text: "# My repository\n\nREADME created without a profile email.\n",
});
await ev(
  'document.querySelector("form.code-widget button[type=submit]").click()',
);
await until(
  'location.search.includes("file=README.md") && document.querySelector(".markdown")?.textContent.includes("README created without a profile email.")',
);
assert.doesNotMatch(
  await ev("document.body.innerText"),
  /Set your profile name and email before committing/,
);
assert.deepEqual(errors, [], "Bootstrap README flow raised browser exceptions");
await writeFile(
  `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-bootstrap-readme.png`,
  Buffer.from(
    (await call("Page.captureScreenshot", { format: "png" })).data,
    "base64",
  ),
);
await cleanupReadme();
console.log(
  "Bootstrap administrator: created README through the browser without a profile email.",
);
ws.close();
