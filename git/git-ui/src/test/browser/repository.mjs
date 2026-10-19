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
  errors = [];
ws.addEventListener("message", (e) => {
  const m = JSON.parse(e.data);
  if (m.method === "Runtime.exceptionThrown")
    errors.push(m.params.exceptionDetails);
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
const tagCreate = '[data-bml-component="git-tag-create"]';
for (const width of [1440, 390]) {
  await call("Emulation.setDeviceMetricsOverride", {
    width, height: 900, deviceScaleFactor: 1, mobile: width === 390,
  });
  await call("Page.navigate", { url: base + "/repositories/branch-counts?tab=branches&ref=feature%2Fone" });
  await until("document.querySelectorAll('.branch-row').length === 5 && document.readyState === 'complete'");
  const branches = await ev(`Array.from(document.querySelectorAll('.branch-row'), row => {
    const delta = row.querySelector('.branch-delta');
    return {
      name: row.querySelector('.ref-name').textContent.trim(),
      ahead: row.querySelector('.delta-ahead')?.textContent.trim() ?? '',
      behind: row.querySelector('.delta-behind')?.textContent.trim() ?? '',
      label: delta?.getAttribute('aria-label') ?? '',
      title: delta?.title ?? '',
      height: delta?.getBoundingClientRect().height ?? 0,
    };
  })`);
  assert.deepEqual(branches.map(({name, ahead, behind}) => ({name, ahead, behind})), [
    {name: "main", ahead: "", behind: ""},
    {name: "feature/one", ahead: "+3", behind: "-2"},
    {name: "ahead-only", ahead: "+1", behind: ""},
    {name: "behind-only", ahead: "", behind: "-1"},
    {name: "in-sync", ahead: "", behind: ""},
  ]);
  assert.equal(branches[1].label, "Commits compared with main: 3 ahead, 2 behind");
  assert.equal(branches[1].title, branches[1].label);
  assert.ok(branches.filter(branch => branch.label).every(branch => branch.height > 0 && branch.height < 24));
  assert.equal(await ev("document.documentElement.scrollWidth > innerWidth"), false, `Branches fit at ${width}px`);
  await writeFile(
    `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-branches-${width}.png`,
    Buffer.from((await call("Page.captureScreenshot", { format: "png" })).data, "base64"),
  );
  await call("Page.navigate", { url: base + "/repositories/repo?tab=tags&ref=feature%2Fone" });
  await until(`document.querySelector('${tagCreate} [data-bml-ref=trigger]') && document.readyState === 'complete'`);
  await ev(`document.querySelector('${tagCreate} [data-bml-ref=trigger]').click()`);
  await until(`document.querySelector('${tagCreate} dialog').matches(':modal')`);
  assert.equal(await ev("document.activeElement?.name"), "name");
  assert.equal(await ev(`document.querySelector('${tagCreate} [name=source]').value`), "feature/one");
  await ev(`
    document.querySelector('${tagCreate} [name=name]').value = 'v1';
    document.querySelector('${tagCreate} [name=tagMessage]').value = 'Keep my release notes';
    document.querySelector('${tagCreate} button[type=submit]').click();
  `);
  await until(`document.querySelector('${tagCreate} [role=alert]')?.textContent.includes('already exists')`);
  assert.ok(await ev(`document.querySelector('${tagCreate} dialog').matches(':modal')`));
  assert.equal(await ev(`document.querySelector('${tagCreate} [name=name]').value`), "v1");
  assert.equal(await ev(`document.querySelector('${tagCreate} [name=source]').value`), "feature/one");
  assert.equal(await ev(`document.querySelector('${tagCreate} [name=tagMessage]').value`), "Keep my release notes");
  assert.equal(await ev("document.documentElement.scrollWidth > innerWidth"), false);
  await writeFile(
    `${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-tag-${width}.png`,
    Buffer.from((await call("Page.captureScreenshot", { format: "png" })).data, "base64"),
  );
  await ev(`document.querySelector('${tagCreate} [data-bml-ref=cancel]').click()`);
  await until(`!document.querySelector('${tagCreate} dialog').open`);
  await ev(`document.querySelector('${tagCreate} [data-bml-ref=trigger]').click()`);
  const tagName = `release/browser-${width}`;
  await ev(`
    document.querySelector('${tagCreate} [name=name]').value = ${JSON.stringify(tagName)};
    document.querySelector('${tagCreate} button[type=submit]').click();
  `);
  await until(`new URLSearchParams(location.search).get('ref') === ${JSON.stringify(tagName)} && document.readyState === 'complete'`);
  assert.equal(await ev("new URLSearchParams(location.search).get('tab')"), "tags");
}
console.log("Tag creation: target defaults, duplicate errors, retained drafts, modal retry, navigation and mobile fit.");
console.log("Branches: ahead/behind counts, default-branch comparison labels, in-sync branches and desktop/mobile fit.");
await call("Emulation.setDeviceMetricsOverride", {
  width: 1440, height: 900, deviceScaleFactor: 1, mobile: false,
});
// Navigate through actual file rows: the whole Studio-style row opens its entry.
await call("Page.navigate", { url: base + "/repositories/repo" });
await until(
  '[...document.querySelectorAll(".tree-list a.tree-name")].some(link => link.textContent.trim() === "README.md")',
);
await until('document.readyState === "complete"');
await ev("document.fonts.ready.then(() => true)");
const readmeRow = await ev(`(() => {
  const link = [...document.querySelectorAll('.tree-list a.tree-name')].find(link => link.textContent.trim() === 'README.md');
  return {link:link.outerHTML, row:link.closest('.tree-row').outerHTML};
})()`);
const readmePoint = await ev(`(() => {
  const link = [...document.querySelectorAll('.tree-list a.tree-name')].find(link => link.textContent.trim() === 'README.md');
  const bounds = link.closest('.tree-row').getBoundingClientRect();
  return {x:bounds.left+bounds.width*0.6,y:bounds.top+bounds.height/2};
})()`);
for (const type of ["mousePressed", "mouseReleased"])
  await call("Input.dispatchMouseEvent", {
    type,
    ...readmePoint,
    button: "left",
    clickCount: 1,
  });
await until('new URLSearchParams(location.search).get("file") === "README.md"');
const readmeNavigation = await ev(`({url:location.href})`);
await writeFile(
  process.env.GIT_UI_ARTIFACT_DIR + "/bosca-git-ui-file-navigation.json",
  JSON.stringify({ readmeRow, readmeNavigation }, null, 2),
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
  new URL(readmeNavigation.url).searchParams.get("file"),
  "README.md",
  "A real pointer click anywhere on the row opens the README",
);
await until('document.querySelector(".file-viewer .markdown-file")');
// Folders open as listings and nested files open from the keyboard.
await call("Page.navigate", { url: base + "/repositories/repo" });
await until(
  '[...document.querySelectorAll(".tree-list a.tree-name")].some(link => link.textContent.trim() === "docs")',
);
await ev(
  '[...document.querySelectorAll(".tree-list a.tree-name")].find(link => link.textContent.trim() === "docs").click()',
);
await until(
  'new URLSearchParams(location.search).get("path") === "docs" && [...document.querySelectorAll(".tree-list a.tree-name")].some(link => link.textContent.trim() === "guide.md")',
);
assert.match(
  await ev('document.querySelector(".path-bar").innerText'),
  /docs/,
  "The path bar shows the opened folder",
);
await ev(
  '[...document.querySelectorAll(".tree-list a.tree-name")].find(link => link.textContent.trim() === "guide.md").focus()',
);
for (const type of ["keyDown", "keyUp"])
  await call("Input.dispatchKeyEvent", {
    type,
    key: "Enter",
    code: "Enter",
    windowsVirtualKeyCode: 13,
  });
await until(
  'new URLSearchParams(location.search).get("file") === "docs/guide.md" && document.querySelector(".file-viewer")',
);
assert.match(
  await ev('document.querySelector(".file-viewer .file-path").textContent'),
  /docs\/guide\.md/,
  "Keyboard navigation opens the nested file",
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
  assert.ok(
    await ev(`[...document.querySelectorAll('.owner-toolbar [role=option]')].some(option => option.textContent === 'All accessible repositories')`),
    "The caller-owned All choice remains available after empty or failed async searches",
  );
}
await ev(
  'document.querySelector(".owner-toolbar .select-query").value="Alice";document.querySelector(".owner-toolbar .select-query").dispatchEvent(new Event("input",{bubbles:true}))',
);
await until(
  `[...document.querySelectorAll('.owner-toolbar [role=option]')].some(option=>option.textContent === 'Alice · @alice')`,
);
await ev(
  `[...document.querySelectorAll('.owner-toolbar [role=option]')].find(option=>option.textContent === 'Alice · @alice').dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}))`,
);
await until(
  'new URLSearchParams(location.search).get("owner") && document.readyState === "complete" && document.querySelector(".owner-toolbar .select-query")',
);
assert.ok(
  await ev('new URLSearchParams(location.search).get("owner")'),
  "Owner selection applies without another submit button",
);
assert.equal(await ev('new URLSearchParams(location.search).get("owner")'), "owner", "The async profile result is selected separately from the persistent All choice");
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
assert.ok(geometry.editor >= 224 && geometry.editor <= 801, JSON.stringify(geometry));
assert.equal(geometry.radius, "0px");
assert.equal(
  await ev(
    'getComputedStyle(document.querySelector(".cm-editor")).backgroundColor',
  ),
  "rgba(20, 24, 34, 0.72)",
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
    assert.ok(size.height >= 224 && size.height <= 801, JSON.stringify(size));
    if (file === "long.txt") assert.ok(size.height >= 799, JSON.stringify(size));
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
for (const width of [1920,1440,1024,390]) {
  await call("Emulation.setDeviceMetricsOverride",{width,height:width===390?844:900,deviceScaleFactor:1,mobile:width===390});
  await call("Page.navigate",{url:base+"/repositories/empty"});
  await until(`location.pathname==='/repositories/empty' && document.readyState==='complete' && document.querySelector('.clone-url-input') && !document.querySelector('[data-bml-ref=signout]').disabled`);
  const initial = await ev(`(() => {const r=document.querySelector('.clone-url-input').getBoundingClientRect();return {width:r.width,height:r.height,value:document.querySelector('.clone-url-input').value}})()`);
  await ev(`window.originalClipboardWrite=navigator.clipboard.writeText;
    navigator.clipboard.writeText=async()=>{throw new DOMException('Denied','NotAllowedError')};
    document.querySelector('.setup-banner .copy-control button').click()`);
  await until(`document.activeElement===document.querySelector('.clone-url-input') && document.activeElement.selectionStart===0 && document.activeElement.selectionEnd===document.activeElement.value.length`);
  assert.ok(await ev(`document.querySelector('.setup-banner [role=status]').textContent.includes('Select and copy')`), "Denied setup copy retains an accessible status and selects the native URL");
  await ev(`navigator.clipboard.writeText=window.originalClipboardWrite;delete window.originalClipboardWrite;
    document.querySelector('.setup-banner .copy-control button').click()`);
  await until(`document.querySelector('.setup-banner .copy-control button').getAttribute('aria-label')==='Copied'`);
  assert.equal(await ev("navigator.clipboard.readText()"),initial.value);
  const final = await ev(`(() => {const r=document.querySelector('.clone-url-input').getBoundingClientRect();return {width:r.width,height:r.height,overflow:document.documentElement.scrollWidth>innerWidth}})()`);
  assert.deepEqual(final,{width:initial.width,height:initial.height,overflow:false}, "Copy success and failure preserve the quick-setup URL geometry");
  await writeFile(`${process.env.GIT_UI_ARTIFACT_DIR}/bosca-git-ui-setup-copy-${width}.png`,Buffer.from((await call("Page.captureScreenshot",{format:"png",captureBeyondViewport:false})).data,"base64"));
}
assert.deepEqual(errors, [], "Quick-setup clipboard checks raised browser exceptions");
console.log("Quick-setup clipboard success, denied-copy selection and stable geometry passed at four widths.");
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
await until('document.body.innerText.includes("Quick setup")');
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
