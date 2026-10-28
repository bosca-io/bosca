import { build } from "esbuild";
import { test } from "node:test";
import assert from "node:assert/strict";
const result = await build({ entryPoints: ["src/main/client/upload.ts"], bundle: true, write: false, format: "esm", platform: "browser" });
const { rawUploadPath, safeArtifactSegment, safeArtifactRepositoryName, artifactTargetResolver, uploadFile } = await import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString("base64")}`);
test("partial upload retry reuses the created repository and changed coordinates resolve again", async () => {
  const calls = [];
  const resolve = artifactTargetResolver(async (...args) => { calls.push(args); return { id: String(calls.length) }; });
  assert.equal((await resolve("new", "namespace", "tool")).id, "1");
  assert.equal((await resolve("new", "namespace", "tool")).id, "1");
  assert.equal((await resolve("new", "namespace", "other")).id, "2");
  assert.equal(calls.length, 2);
  let attempts = 0;
  const retry = artifactTargetResolver(async () => { if (++attempts === 1) throw new Error("Unavailable"); return { id: "created" }; });
  await assert.rejects(retry("new", "namespace", "tool"), /Unavailable/);
  assert.equal((await retry("new", "namespace", "tool")).id, "created");
});
test("new repository names match the target contract before creating a repository", () => {
  for (const value of ["release-tools", "@scope.tool_1", "a".repeat(255)]) assert.equal(safeArtifactRepositoryName(value), true);
  for (const value of ["", " ", "a b", "a/b", "a\\b", "a..b", "a".repeat(256)]) assert.equal(safeArtifactRepositoryName(value), false);
});
test("upload coordinates are encoded and unsafe path segments fail before network I/O", () => {
  assert.equal(rawUploadPath("@scope", "tool", "1.0+build", "a b.zip"), "/_artifacts/raw/%40scope/tool/1.0%2Bbuild/a%20b.zip");
  for (const value of ["", " ", ".", "..", "a/b", "a\\b", "a\u0000b", "a\u007fb"]) {
    assert.equal(safeArtifactSegment(value), false, JSON.stringify(value));
    assert.throws(() => rawUploadPath("ns", "repo", value, "file.zip"));
  }
});
function xhr() {
  let current;
  globalThis.XMLHttpRequest = class {
    upload = {}; headers = {}; status = 201; responseText = "";
    constructor() { current = this; }
    open(method, url) { this.method = method; this.url = url; }
    setRequestHeader(name, value) { this.headers[name] = value; }
    send(file) { this.file = file; }
    abort() { this.onabort(); }
  };
  return () => current;
}
test("uploads forward SDK auth, MIME type, progress, and real registry failures", async () => {
  const request = xhr(); const progress = [];
  const file = { type: "application/zip" };
  const pending = uploadFile("/_artifacts/raw/ns/repo/1/file.zip", file, { Authorization: "Bearer session" }, new AbortController().signal, value => progress.push(value));
  assert.equal(request().headers.Authorization, "Bearer session");
  assert.equal(request().headers["Content-Type"], "application/zip");
  request().upload.onprogress({ lengthComputable: true, loaded: 5, total: 10 });
  request().onload(); await pending;
  assert.deepEqual(progress, [50, 100]);
  const failed = uploadFile("/upload", file, {}, new AbortController().signal, () => {});
  request().status = 403; request().responseText = "Permission denied"; request().onload();
  await assert.rejects(failed, /Permission denied/);
});
test("cancellation aborts in-flight uploads and prevents sending an already-cancelled upload", async () => {
  const request = xhr(); const controller = new AbortController();
  const pending = uploadFile("/upload", { type: "" }, {}, controller.signal, () => {});
  controller.abort(); await assert.rejects(pending, /cancelled/);
  const cancelled = uploadFile("/upload", { type: "" }, {}, controller.signal, () => {});
  await assert.rejects(cancelled, /cancelled/);
  assert.equal(request().file, undefined);
});
