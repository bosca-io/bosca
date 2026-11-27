import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_CUTOVER_URL, control = process.env.ATLAS_CUTOVER_CONTROL;
const key = process.env.ATLAS_CUTOVER_SPEC, metadata = process.env.ATLAS_CUTOVER_METADATA;
assert.ok([base, control].every(value => value && ['localhost', '127.0.0.1'].includes(new URL(value).hostname)) && key && metadata,
  'Use only the isolated cutover fixture.');
const reports = process.env.ATLAS_CUTOVER_REPORTS, path = `/workops/specs/${key}`;
const owned = await launchTestBrowser(0);
let context = owned.connection.contexts()[0], page = context.pages()[0];
const errors = [], sockets = new Set(), evidence = [];
const observe = tab => {
  tab.on('pageerror', error => errors.push(error.message));
  tab.on('websocket', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
};
observe(page);
const prepareHandoff = () => context.addInitScript(() => {
  const sockets = new Set();
  globalThis.workOpsCutoverSockets = sockets;
  globalThis.WebSocket = new Proxy(globalThis.WebSocket, {
    construct(target, args, newTarget) {
      const socket = Reflect.construct(target, args, newTarget);
      sockets.add(socket); socket.addEventListener('close', () => sockets.delete(socket), { once: true }); return socket;
    },
  });
});
const gql = async (query, variables = {}) => {
  const cookies = await context.cookies(base), token = cookies.find(cookie => cookie.name === '_atlas_test')?.value;
  assert.ok(token, 'The real login must establish the shared configured cookie.');
  const response = await context.request.post(base + '/graphql', { headers: { Authorization: `Bearer ${token}` }, data: { query, variables } });
  assert.equal(response.status(), 200); const result = await response.json(); assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
const read = () => gql('query($id:UUID!){security{principals{current{id}}} content{metadata(id:$id,version:1){id document{title content}}}}', { id: metadata });
const queue = async phase => (await gql('mutation($project:UUID!,$summary:String!){workOps{tasks{create(input:{projectId:$project,summary:$summary}){id}}}}',
  { project: process.env.ATLAS_CUTOVER_PROJECT, summary: `Routing rehearsal ${phase} background task` })).workOps.tasks.create.id;
const switchTo = async name => {
  const session = await context.storageState();
  await page.evaluate(() => { for (const socket of globalThis.workOpsCutoverSockets) socket.close(1000, 'UI routing handoff'); });
  await page.waitForFunction(() => globalThis.workOpsCutoverSockets.size === 0);
  for (let i = 0; sockets.size && i < 100; i++) await new Promise(resolve => setTimeout(resolve, 50));
  assert.equal(sockets.size, 0, `Drain sessions before changing UI routing: ${[...sockets].map(socket => socket.url()).join(', ')}`);
  await context.close();
  // Close the previous UI's tabs deliberately, including history documents, before switching.
  assert.equal(page.isClosed(), true); assert.equal(context.pages().length, 0);
  const response = await fetch(control + '/switch', { method: 'POST', body: name, signal: AbortSignal.timeout(10000) }); assert.equal(response.status, 204);
  context = await owned.connection.newContext({ storageState: session }); await prepareHandoff(); page = await context.newPage(); observe(page);
};
const studio = async phase => {
  await page.goto(base + path); const editor = page.locator('.ProseMirror[contenteditable="true"]').first(); await editor.waitFor();
  await editor.getByText(phase === 'before' ? 'Saved before switching UI routing.' : 'Saved through Atlas after routing switch.', { exact: true }).waitFor();
  const state = await read(); evidence.push({ phase, state, taskId: await queue(phase) });
  await page.screenshot({ path: resolve(reports, `${phase}-studio.png`), fullPage: true });
};
try {
  await prepareHandoff();
  await page.goto(base + '/auth/login'); await page.getByPlaceholder('you@company.com').fill('admin');
  await page.locator('input[type=password]').fill(process.env.ATLAS_WORKOPS_TEST_PASSWORD);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click(); await page.waitForURL(url => !url.pathname.startsWith('/auth'));
  await studio('before');
  await switchTo('atlas'); await page.goto(base + path);
  const editor = page.locator('[data-workops-document] [data-document-ready] .ProseMirror[contenteditable="true"]'); await editor.waitFor();
  await editor.getByText('Saved before switching UI routing.', { exact: true }).waitFor();
  assert.equal((await read()).security.principals.current.id, evidence[0].state.security.principals.current.id);
  const paragraph = editor.locator(':scope > p').filter({ hasText: 'Saved before switching UI routing.' });
  await paragraph.click(); await page.keyboard.press('Home'); await page.keyboard.press('Shift+End');
  await page.keyboard.type('Saved through Atlas after routing switch.');
  await page.getByRole('button', { name: 'Save document', exact: true }).click(); await page.getByText('Document saved.', { exact: true }).waitFor();
  const state = await read(); assert.match(JSON.stringify(state.content.metadata.document.content), /Saved through Atlas after routing switch/);
  evidence.push({ phase: 'atlas', state, taskId: await queue('atlas') }); await page.screenshot({ path: resolve(reports, 'atlas.png'), fullPage: true });
  await switchTo('studio'); await studio('restored');
  assert.equal(evidence[2].state.security.principals.current.id, evidence[0].state.security.principals.current.id);
  assert.deepEqual(evidence[2].state.content.metadata, evidence[1].state.content.metadata);
  assert.deepEqual(errors, []);
  await writeFile(resolve(reports, 'browser-evidence.json'), JSON.stringify(evidence, null, 2));
  console.log('Real Studio → packaged Atlas → restored Studio routing retained the authenticated identity and saved Content document.');
} catch (error) {
  await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {});
  console.error(await page.locator('body').innerText().catch(() => 'Page unavailable')); throw error;
} finally { await owned.close(); }
