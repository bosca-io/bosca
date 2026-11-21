import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, readerGroup = process.env.ATLAS_WORKOPS_TEST_READER_GROUP;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && readerGroup, 'Use the isolated Atlas approval fixture.');
const reports = resolve('build/reports/approvals-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), page = await context.newPage(), readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(), errors = [];
for (const tab of [page, reader]) { tab.on('pageerror', error => errors.push(error.message)); tab.on('console', message => { if (message.type() === 'error') errors.push(message.text()); }); }
const gql = async (query, variables = {}, tab = page) => {
  const result = await tab.evaluate(async ({ query, variables }) => (await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) })).json(), { query, variables });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
const path = id => `/workops/approvals/${id}`;
const login = async (tab, isReader = false) => { await tab.goto(base + '/workops/inbox'); await tab.locator('input[name=identifier]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER : 'admin'); await tab.locator('input[name=password]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD : process.env.ATLAS_WORKOPS_TEST_PASSWORD); await tab.locator('button[type=submit]').click(); await tab.waitForURL(url => url.pathname === '/workops/inbox'); };
const read = async id => (await gql('query($id:UUID!){pipelines{run(runId:$id){id status error awaitingNodes{nodeId type name runId}}}}', { id })).pipelines.run;
const settled = async (tab = page) => { await tab.waitForFunction(() => !document.querySelector('[data-bml-action-pending]')); await tab.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))); };
const panel = (tab = page) => tab.getByRole('dialog', { name: 'Confirm approval decision', exact: true });
const open = async (approved = true, tab = page) => { await tab.getByRole('button', { name: approved ? 'Approve' : 'Reject', exact: true }).click(); await panel(tab).waitFor(); return panel(tab); };
const confirm = async (approved = true, tab = page) => { await panel(tab).getByRole('checkbox', { name: approved ? 'Confirm approval' : 'Confirm rejection', exact: true }).check(); await panel(tab).getByRole('button', { name: approved ? 'Approve' : 'Reject', exact: true }).click(); };
const resolveGate = async (runId, approved = true) => gql('mutation($id:UUID!,$approved:Boolean!){pipelines{resolveGate(runId:$id,nodeId:"gate",approved:$approved)}}', { id: runId, approved });
const savePipeline = async (name, middle) => (await gql('mutation($input:PipelineInput!){pipelines{save(input:$input){id}}}', { input: {
  name, acceptedInputType: 'JSON', graph: { nodes: [{ id: 'in', type: 'input', acceptedType: 'JSON' }, middle, { id: 'out', type: 'output', outputType: 'JSON' }], edges: [{ id: 'e1', source: 'in', target: middle.id }, { id: 'e2', source: middle.id, target: 'out' }] },
} })).pipelines.save.id;
const grant = async (id, group) => gql('mutation($permission:PermissionInput!){pipelines{addPermission(permission:$permission)}}', { permission: { entityId: id, groupId: group, action: 'EXECUTE' } });
const start = async id => {
  const value = (await gql('mutation($id:UUID!){pipelines{run(id:$id,payload:{release:"scratch",retained:true}){ok runId status error}}}', { id })).pipelines.run;
  assert.notEqual(value.runId, '00000000-0000-0000-0000-000000000000', JSON.stringify(value));
  await page.waitForFunction(async id => { const r = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query: 'query($id:UUID!){pipelines{run(runId:$id){status awaitingNodes{type}}}}', variables: { id } }) }); const v = (await r.json()).data?.pipelines?.run; return v?.status === 'SUSPENDED' && v.awaitingNodes.some(n => n.type === 'gate.approval'); }, value.runId);
  return value.runId;
};
const terminal = async (id, expected) => { await page.waitForFunction(async ({ id, expected }) => { const r = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query: 'query($id:UUID!){pipelines{run(runId:$id){status}}}', variables: { id } }) }); return (await r.json()).data?.pipelines?.run?.status === expected; }, { id, expected }); };
const geometry = async tab => { assert.equal(await tab.evaluate(() => document.documentElement.scrollWidth > innerWidth), false); };
let readerId, adminGroup, granted = false;
const admin = async add => { await gql(`mutation($group:UUID!,$principal:UUID!){security{${add ? 'add' : 'remove'}PrincipalGroup(groupId:$group,principalId:$principal)}}`, { group: adminGroup, principal: readerId }); granted = add; };
try {
  await login(page); await login(reader, true);
  readerId = (await gql('{security{principals{current{id}}}}', {}, reader)).security.principals.current.id;
  adminGroup = (await gql('{security{groups{find(nameOrDescription:"administrators",offset:0,limit:100,type:SYSTEM){id name}}}}')).security.groups.find.find(group => group.name === 'administrators')?.id; assert.ok(adminGroup);
  const pipeline = await savePipeline('Atlas approval fixture', { id: 'gate', type: 'gate.approval', prompt: 'Review <release> & continue?' });
  await grant(pipeline, adminGroup); await grant(pipeline, readerGroup);
  const first = await start(pipeline);
  await page.waitForFunction(async id => { const r = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query: '{workOps{notifications{mine(offset:0,limit:200){event link}}}}' }) }); return (await r.json()).data.workOps.notifications.mine.some(n => n.event === 'PIPELINE_APPROVAL_REQUESTED' && n.link === `/pipelines/runs?runId=${id}`); }, first);
  await page.goto(base + '/workops/inbox'); const notification = page.locator('.workops-notification').filter({ has: page.locator(`a[href="${path(first)}"]`) }); await notification.waitFor();
  await notification.locator('a.workops-notification-body').click(); await page.waitForURL(url => url.pathname === path(first)); await page.getByText('Review <release> & continue?', { exact: true }).waitFor();
  assert.equal(await page.getByRole('link', { name: 'Back to inbox', exact: true }).getAttribute('href'), '/workops/inbox');
  let decision = await open(); await page.keyboard.press('Escape'); await decision.waitFor({ state: 'hidden' }); await settled();
  assert.equal(await page.getByRole('button', { name: 'Refresh approval', exact: true }).evaluate(node => node === document.activeElement), true);
  await page.getByRole('button', { name: 'Approve', exact: true }).focus(); await page.keyboard.press('Enter'); await decision.waitFor();
  await decision.locator('textarea[name=note]').fill('Approved from Atlas'); await confirm(); await decision.waitFor({ state: 'hidden' }); await page.getByText('Approval recorded.', { exact: true }).waitFor(); await terminal(first, 'OK');
  await page.getByRole('button', { name: 'Refresh approval', exact: true }).click(); await settled(); await page.getByText('Completed', { exact: true }).waitFor(); assert.equal(await page.getByRole('button', { name: 'Approve', exact: true }).count(), 0);
  const rejected = await start(pipeline); await page.goto(base + path(rejected)); decision = await open(false); await decision.locator('textarea[name=note]').fill('Release <not ready> & retry later'); await confirm(false); await decision.waitFor({ state: 'hidden' }); await page.getByText('Rejection recorded.', { exact: true }).waitFor(); await terminal(rejected, 'FAILED');
  assert.match((await read(rejected)).error, /Gate rejected: Release <not ready> & retry later/); await page.reload(); await page.getByText('Gate rejected: Release <not ready> & retry later', { exact: true }).waitFor();

  const parentPipeline = await savePipeline('Atlas nested approval fixture', { id: 'child', type: 'runPipeline', pipelineId: pipeline });
  const parent = await start(parentPipeline), child = (await read(parent)).awaitingNodes.find(node => node.type === 'gate.approval').runId; assert.ok(child && child !== parent);
  await page.goto(base + path(parent)); decision = await open(); await decision.locator('textarea[name=note]').fill('Stale decision retained'); await resolveGate(child); await terminal(parent, 'OK'); await confirm();
  await decision.getByRole('alert').filter({ hasText: 'This approval is no longer waiting.' }).waitFor(); assert.equal(await decision.locator('textarea[name=note]').inputValue(), 'Stale decision retained');
  await decision.getByRole('button', { name: 'Cancel', exact: true }).click(); await decision.waitFor({ state: 'hidden' }); assert.equal((await read(child)).status, 'OK');

  const pending = await start(parentPipeline); const pendingChild = (await read(pending)).awaitingNodes.find(node => node.type === 'gate.approval').runId; assert.ok(pendingChild && pendingChild !== pending);
  await reader.goto(base + path(pending)); await reader.getByText('This approval request is unavailable. Refresh to try again.', { exact: true }).waitFor(); assert.equal(await reader.getByRole('button', { name: 'Approve', exact: true }).count(), 0);
  await admin(true); await reader.getByRole('button', { name: 'Refresh approval', exact: true }).click(); await settled(reader); decision = await open(true, reader); await decision.locator('textarea[name=note]').fill('Retained after access loss'); await admin(false); await confirm(true, reader);
  await decision.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); await settled(reader); assert.equal(await decision.locator('textarea[name=note]').inputValue(), 'Retained after access loss'); assert.equal((await read(pendingChild)).status, 'SUSPENDED');
  await admin(true); await decision.getByRole('button', { name: 'Reload approval request', exact: true }).click(); await settled(reader); assert.equal(await decision.locator('textarea[name=note]').inputValue(), 'Retained after access loss'); await confirm(true, reader); await decision.waitFor({ state: 'hidden' }); await terminal(pending, 'OK'); await admin(false);
  await page.goto(base + path('00000000-0000-0000-0000-000000000099')); await page.getByRole('heading', { name: 'Run unavailable', exact: true }).waitFor();

  const capture = await start(parentPipeline);
  for (const appearance of ['dark', 'light']) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]);
    for (const width of [1920, 1024, 768, 390]) {
      await page.setViewportSize({ width, height: 1000 }); await page.goto(base + path(capture)); await page.getByText('Review <release> & continue?', { exact: true }).waitFor(); await geometry(page); assert.equal(await page.locator('html').getAttribute('data-theme'), appearance);
      await page.screenshot({ path: resolve(reports, `${appearance}-${width}-waiting.png`), fullPage: true });
      for (const approved of [true, false]) {
        decision = await open(approved); await decision.locator('textarea[name=note]').fill('Review note');
        const r = await decision.evaluate(node => { const r = node.getBoundingClientRect(); return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: innerWidth, height: innerHeight }; }); assert.ok(r.left >= 0 && r.right <= r.width + 1 && r.top >= 0 && r.bottom <= r.height + 1, JSON.stringify(r)); await geometry(page);
        await page.screenshot({ path: resolve(reports, `${appearance}-${width}-${approved ? 'approve' : 'reject'}.png`), fullPage: true });
        await decision.getByRole('button', { name: 'Cancel', exact: true }).click(); await decision.waitFor({ state: 'hidden' });
      }
    }
  }
  assert.equal((await read(capture)).status, 'SUSPENDED'); assert.deepEqual(errors, []);
  console.log('Actual approval notifications, platform gate decisions, nested run identity, stale/access-loss recovery and responsive palettes passed.');
} catch (error) { await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {}); console.error(await page.locator('body').innerText().catch(() => 'Page unavailable')); throw error; }
finally { if (granted) await admin(false); await owned.close(); }
