import { spawn } from "node:child_process";
import { mkdtemp, mkdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createServer } from "node:net";

const base = process.env.GIT_UI_BASE_URL;
if (
  !base ||
  !["localhost", "127.0.0.1", "[::1]"].includes(new URL(base).hostname)
) {
  throw new Error(
    "Set GIT_UI_BASE_URL to the isolated runBrowserFixture URL on localhost.",
  );
}
const directory = dirname(fileURLToPath(import.meta.url));
const artifacts = resolve(
  process.env.GIT_UI_ARTIFACT_DIR ?? "build/reports/browser",
);
await mkdir(artifacts, { recursive: true });
const profile = await mkdtemp(join(tmpdir(), "bosca-git-ui-browser-"));
const listener = createServer();
await new Promise((resolve) => listener.listen(0, "127.0.0.1", resolve));
const port = listener.address().port;
await new Promise((resolve) => listener.close(resolve));
const cdp = `http://127.0.0.1:${port}`;
const binary =
  process.env.GIT_UI_CHROME_BINARY ??
  (process.platform === "darwin"
    ? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    : "google-chrome");
const browser = spawn(
  binary,
  [
    "--headless=new",
    "--disable-gpu",
    "--no-first-run",
    "--no-default-browser-check",
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profile}`,
    "about:blank",
  ],
  { stdio: "ignore" },
);
let launchError;
browser.on("error", (error) => {
  launchError = error;
});
try {
  let ready = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    if (launchError) throw launchError;
    try {
      ready = (await fetch(`${cdp}/json/version`)).ok;
      if (ready) break;
    } catch {
      /* Browser is starting. */
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  if (!ready) throw new Error("The isolated Chrome instance did not start.");
  const available = [
    "auth.mjs",
    "repository.mjs",
    "statistics.mjs",
    "pulls.mjs",
    "search.mjs",
    "screens.mjs",
  ];
  const selected = process.env.GIT_UI_BROWSER_TESTS?.split(",") ?? available;
  for (const test of selected) {
    if (!available.includes(test)) throw new Error(`Unknown browser test: ${test}`);
    const child = spawn(process.execPath, [join(directory, test)], {
      env: {
        ...process.env,
        GIT_UI_CDP_URL: cdp,
        GIT_UI_ARTIFACT_DIR: artifacts,
      },
      stdio: "inherit",
    });
    const code = await new Promise((resolve, reject) => {
      child.on("error", reject);
      child.on("exit", resolve);
    });
    if (code !== 0) throw new Error(`${test} failed (${code}).`);
  }
  console.log(`Browser artifacts: ${artifacts}`);
} finally {
  // This process owns the new profile and browser; it never attaches to the user's Chrome session.
  browser.kill("SIGTERM");
  await new Promise((resolve) => {
    if (browser.exitCode !== null) resolve();
    else {
      browser.once("exit", resolve);
      setTimeout(resolve, 3000);
    }
  });
  await rm(profile, { recursive: true, force: true });
}
