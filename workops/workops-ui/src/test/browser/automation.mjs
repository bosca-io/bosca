import { compactResult } from './presentation-checks.mjs';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';
import { automationFixture } from './automation-fixture.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, fixtureProject = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && fixtureProject, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/automation-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), page = await context.newPage(), readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(), errors = [];
for (const tab of [page, reader]) { tab.on('pageerror', error => errors.push(error.message)); tab.on('console', message => { if (message.type() === 'error') errors.push(message.text()); }); }
const gql = async (query, variables = {}, tab = page) => {
  const result = await tab.evaluate(async ({ query, variables }) => (await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) })).json(), { query, variables });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
const path = c => `/workops/settings/automation?scope=${c.scope}&scopeId=${c.scopeId ?? ''}`;
const login = async (tab, isReader = false) => {
  await tab.goto(base + '/workops/settings/automation'); await tab.locator('input[name=identifier]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER : 'admin');
  await tab.locator('input[name=password]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD : process.env.ATLAS_WORKOPS_TEST_PASSWORD); await tab.locator('button[type=submit]').click(); await tab.waitForURL(url => url.pathname === '/workops/settings/automation');
};
const row = (id, tab = page) => tab.locator(`[data-workops-automation-rule="${id}"]`);
const dialog = (name, tab = page) => tab.getByRole('dialog', { name, exact: true });
const json = (panel, name) => panel.locator(`[data-bml-json-editor][data-name="${name}"] .cm-content[contenteditable=true]`);
const setJson = async (panel, name, text) => {
  await json(panel, name).fill(text);
  await panel.page().waitForFunction(({ name, text }) => [...document.querySelectorAll('dialog[open] textarea[name]')].some(field => field.name === name && field.value === text), { name, text });
};
const choose = async (panel, label, value) => { await panel.getByRole('combobox', { name: label, exact: true }).click(); await panel.page().getByRole('option', { name: value, exact: true }).click(); };
const profile = async panel => {
  const name = panel.page() === reader ? 'WorkOps Reader' : 'admin';
  await panel.getByRole('combobox', { name: 'Run as profile', exact: true }).click(); await panel.getByRole('combobox', { name: 'Search run as profile', exact: true }).fill(name); await panel.page().getByRole('option', { name, exact: true }).click();
};
const settled = async panel => {
  await panel.locator('[data-bml-action-pending]').waitFor({ state: 'detached' });
  await panel.page().evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
};
const ready = async panel => { await panel.waitFor(); await panel.locator('[data-json-ready]').nth(2).waitFor(); await settled(panel); };
const open = async (tab = page) => { await tab.getByRole('button', { name: 'New rule', exact: true }).click(); const panel = dialog('New automation rule', tab); await ready(panel); return panel; };
const edit = async (id, name, tab = page) => { await row(id, tab).getByRole('button', { name: `Edit ${name}`, exact: true }).click(); const panel = dialog('Edit automation rule', tab); await ready(panel); return panel; };
const fill = async (panel, name) => {
  await panel.locator('input[name=name]').fill(name); await panel.locator('textarea[name=description]').fill(' Automation <copy> '); await panel.getByRole('checkbox', { name: 'Enabled', exact: true }).uncheck();
  await choose(panel, 'On action failure', 'Continue'); await panel.locator('input[name=fires]').fill('7'); await panel.locator('input[name=retention]').fill('12'); await profile(panel);
  await setJson(panel, 'trigger', JSON.stringify({ type: 'TaskCreated', filter: 'summary ~ "atlas"' }));
  await setJson(panel, 'conditions', JSON.stringify([{ type: 'Always' }])); await setJson(panel, 'actions', JSON.stringify([{ type: 'AddComment', template: 'Automation <note>' }]));
  await choose(panel, 'Trigger', 'Task updated');
  await panel.page().waitForFunction(() => { const f = document.querySelector('dialog[open] textarea[name=trigger]'); try { return f && JSON.parse(f.value).type === 'TaskUpdated'; } catch { return false; } });
  await settled(panel);
  assert.equal((await panel.getByRole('combobox', { name: 'Run as profile', exact: true }).innerText()).trim(), panel.page() === reader ? 'WorkOps Reader' : 'admin');
  assert.equal(JSON.parse(await panel.locator('textarea[name=trigger]').inputValue()).filter, 'summary ~ "atlas"');
};
const save = async panel => { await panel.getByRole('button', { name: 'Save rule', exact: true }).click(); await panel.waitFor({ state: 'hidden' }); const link = panel.page().getByRole('link', { name: 'Show the saved rule', exact: true }); await link.waitFor(); await compactResult(link); return new URL(await link.getAttribute('href'), base).searchParams.get('item'); };
const cancel = async panel => { await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await panel.waitFor({ state: 'hidden' }); };
const reject = async (panel, name = 'Save rule') => { await panel.getByRole('button', { name, exact: true }).click(); await panel.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); await settled(panel); };
const history = async (id, name, tab = page) => { await row(id, tab).getByRole('button', { name: `Run history for ${name}`, exact: true }).click(); const panel = dialog('Run history', tab); await panel.waitFor(); return panel; };
const geometry = async (panel, name) => {
  const r = await panel.evaluate(node => { const r = node.getBoundingClientRect(); return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: innerWidth, height: innerHeight, scroll: document.documentElement.scrollWidth }; });
  assert.ok(r.left >= 0 && r.right <= r.width + 1 && r.scroll <= r.width + 1 && r.top >= 0 && r.bottom <= r.height + 1, name + JSON.stringify(r));
};
let fixture;
try {
  await login(page); await login(reader, true); const readerId = (await gql('{security{principals{current{id}}}}', {}, reader)).security.principals.current.id;
  const readerProfile = (await gql('{profiles{current{id}}}', {}, reader)).profiles.current[0].id; assert.notEqual(readerProfile, readerId);
  fixture = await automationFixture(gql, fixtureProject, readerId);
  assert.equal(await page.getByRole('button', { name: 'Choose scope', exact: true }).count(), 0);
  const projectScope = fixture.scopes.find(value => value.scope === 'PROJECT');
  await choose(page.locator('.scope-filter'), 'Scope', 'Project');
  await page.waitForURL(url => url.searchParams.get('scope') === 'PROJECT' && !url.searchParams.get('scopeId'));
  assert.equal(await page.locator('.scope-filter input[name=scopeId]').count(), 0);
  const choiceLabel = await page.locator('.scope-filter select[name=scopeId] option').evaluateAll((options,id) => options.find(option => option.value === id)?.textContent, projectScope.scopeId);
  assert.ok(choiceLabel); await choose(page.locator('.scope-filter'), 'Project', choiceLabel);
  await page.waitForURL(url => url.searchParams.get('scope') === 'PROJECT' && url.searchParams.get('scopeId') === projectScope.scopeId);
  await choose(page.locator('.scope-filter'), 'Scope', 'Global');
  await page.waitForURL(url => url.searchParams.get('scope') === 'GLOBAL' && !url.searchParams.get('scopeId'));

  for (const c of fixture.scopes) {
    console.log('Automation scope', c.scope); await page.goto(base + path(c)); let panel = await open(); await fill(panel, ` Atlas ${c.scope} rule `); c.id = await save(panel); c.name = `Atlas ${c.scope} rule`;
    let stored = (await fixture.read(c)).find(v => v.id === c.id);
    const expected = fixture.input(c, c.name, { conditions: [{ type: 'Always' }], actions: [{ type: 'AddComment', template: 'Automation <note>' }] }); for (const [key, value] of Object.entries(expected)) assert.deepEqual(stored[key], value, c.scope + '.' + key);
    panel = await edit(c.id, c.name); assert.equal(await panel.locator('input[name=scope]').getAttribute('type'), 'hidden'); assert.equal(await panel.locator('input[name=scopeId]').inputValue(), c.scopeId ?? '');
    await setJson(panel, 'trigger', '{ invalid trigger'); await choose(panel, 'Trigger', 'Task deleted'); await panel.getByRole('alert').filter({ hasText: 'Fix the trigger JSON before changing its type.' }).waitFor(); await settled(panel);
    assert.equal(await panel.locator('textarea[name=trigger]').inputValue(), '{ invalid trigger'); await setJson(panel, 'trigger', JSON.stringify(expected.trigger));
    await setJson(panel, 'conditions', '[ invalid condition'); await reject(panel); assert.ok((await json(panel, 'conditions').innerText()).includes('[ invalid condition'));
    await setJson(panel, 'conditions', '[]'); await panel.locator('input[name=name]').click(); await panel.locator('input[name=name]').fill('Retained stale rule');
    assert.equal(await panel.locator('input[name=name]').inputValue(), 'Retained stale rule'); await fixture.update(stored, { name: 'External rule update' });
    await panel.getByRole('button', { name: 'Retry loading rules', exact: true }).click(); await panel.page().waitForFunction(() => !document.querySelector('[data-bml-action-pending]')); await page.waitForFunction(() => document.querySelector('dialog[open] input[name=name]')?.getAttribute('value') === 'Retained stale rule');
    await reject(panel); assert.equal(await panel.locator('input[name=name]').inputValue(), 'Retained stale rule'); assert.equal((await fixture.read(c)).find(v => v.id === c.id).name, 'External rule update'); await cancel(panel);
    await page.getByRole('button', { name: 'Refresh rules', exact: true }).click(); panel = await edit(c.id, 'External rule update'); await panel.locator('input[name=name]').fill(c.name); await save(panel);
    panel = await history(c.id, c.name); await panel.getByText('No executions recorded yet.', { exact: true }).waitFor(); await panel.getByRole('button', { name: 'Close', exact: true }).click(); await panel.waitFor({ state: 'hidden' });

    await fixture.access(c, 'VIEW', true); await reader.goto(base + path(c)); panel = await open(reader); await fill(panel, `Reader ${c.scope} rule`);
    if (c.scope === 'GLOBAL') await fixture.access(c, 'MANAGE', false);
    await reject(panel); assert.equal(await panel.locator('input[name=name]').inputValue(), `Reader ${c.scope} rule`); assert.equal(JSON.parse(await panel.locator('textarea[name=trigger]').inputValue()).filter, 'summary ~ "atlas"');
    await fixture.access(c, 'MANAGE', true); const disposable = await save(panel); assert.equal((await fixture.read(c)).find(v => v.id === disposable).runAsProfileId, readerProfile);
    await fixture.access(c, 'MANAGE', false); await row(disposable, reader).getByRole('button', { name: `Enable Reader ${c.scope} rule`, exact: true }).click(); await reader.getByRole('alert').filter({ hasText: /.+/ }).waitFor();
    assert.equal((await fixture.read(c)).find(v => v.id === disposable).enabled, false);
    await fixture.access(c, 'MANAGE', true); await row(disposable, reader).getByRole('button', { name: `Enable Reader ${c.scope} rule`, exact: true }).click(); await row(disposable, reader).getByRole('button', { name: `Disable Reader ${c.scope} rule`, exact: true }).waitFor(); assert.equal((await fixture.read(c)).find(v => v.id === disposable).enabled, true);
    await row(disposable, reader).getByRole('button', { name: `Delete Reader ${c.scope} rule`, exact: true }).click(); panel = dialog('Delete automation rule', reader); await panel.waitFor(); await cancel(panel); assert.ok((await fixture.read(c)).some(v => v.id === disposable));
    await row(disposable, reader).getByRole('button', { name: `Delete Reader ${c.scope} rule`, exact: true }).click(); await panel.waitFor(); await panel.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await fixture.access(c, 'MANAGE', false); await reject(panel, 'Delete rule');
    assert.ok((await fixture.read(c)).some(v => v.id === disposable)); await fixture.access(c, 'MANAGE', true); await panel.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await panel.getByRole('button', { name: 'Delete rule', exact: true }).click(); await panel.waitFor({ state: 'hidden' }); assert.equal((await fixture.read(c)).some(v => v.id === disposable), false);
    await fixture.access(c, 'MANAGE', false); if (c.scope !== 'GLOBAL') await fixture.access(c, 'VIEW', false);
    await reader.goto(base + path(c)); await reader.getByText('Rules have not been loaded. Choose an available scope or refresh to try again.', { exact: true }).waitFor(); assert.equal(await reader.locator('[data-workops-automation-rule]').count(), 0); assert.equal(await reader.getByText('No automation rules in this scope yet.', { exact: true }).count(), 0);
  }

  const c = fixture.scopes.find(v => v.scope === 'PROJECT');
  const privateActor = (await gql('mutation{profiles{add(linkToPrincipal:false,profile:{name:"Private automation actor",visibility:USER,attributes:[]}){id}}}')).profiles.add.id;
  await fixture.update((await fixture.read(c)).find(v => v.id === c.id), { runAsProfileId: privateActor });
  await fixture.access(c, 'VIEW', true); await fixture.access(c, 'MANAGE', true); await reader.goto(`${base}${path(c)}&item=${c.id}`);
  let privatePanel = await edit(c.id, c.name, reader); await privatePanel.getByRole('combobox', { name: 'Run as profile', exact: true }).filter({ hasText: 'Profile unavailable' }).waitFor();
  assert.equal(await privatePanel.getByText('Private automation actor', { exact: true }).count(), 0);
  await privatePanel.locator('input[name=name]').click(); await privatePanel.locator('input[name=name]').fill('Private actor retained rule'); await save(privatePanel);
  let privateStored = (await fixture.read(c)).find(v => v.id === c.id); assert.equal(privateStored.name, 'Private actor retained rule'); assert.equal(privateStored.runAsProfileId, privateActor);
  assert.deepEqual(privateStored.actions, [{ type: 'AddComment', template: 'Automation <note>' }]);
  await fixture.access(c, 'MANAGE', false); await fixture.access(c, 'VIEW', false); await fixture.update(privateStored, { name: c.name, runAsProfileId: fixture.profile });
  const measured = await fixture.create(c, 'Measured execution history', { enabled: true, trigger: { type: 'TaskCreated' } });
  const failed = await fixture.create(c, 'Pending action history', { enabled: true, trigger: { type: 'TaskCreated' }, actions: [{ type: 'RunScript', scriptKey: 'fixture-pending' }] });
  await gql('mutation($project:UUID!){workOps{tasks{create(input:{projectId:$project,summary:"Automation real execution"}){id}}}}', { project: fixture.project });
  let records = await fixture.logs(measured); assert.equal(records[0].outcome, 'OK'); assert.ok(records[0].startedAt && records[0].finishedAt); assert.ok(Number.isInteger(records[0].durationMs) && records[0].durationMs >= 0);
  assert.equal((await fixture.logs(failed))[0].outcome, 'NOT_IMPLEMENTED'); await fixture.update((await fixture.read(c)).find(v => v.id === failed), { enabled: false });
  for (let index = 0; index < 51; index++) await gql('mutation($project:UUID!,$name:String!){workOps{tasks{create(input:{projectId:$project,summary:$name}){id}}}}', { project: fixture.project, name: `Automation history ${index}` });
  assert.equal((await fixture.logs(measured)).length, 52); await fixture.update((await fixture.read(c)).find(v => v.id === measured), { enabled: false });
  for (let index = 0; index < 27; index++) await fixture.create(c, `Paged automation ${index}`);
  await page.goto(base + path(c)); assert.equal(await page.locator('[data-workops-automation-rule]').count(), 25); await page.getByRole('navigation', { name: 'Automation rule pages', exact: true }).getByRole('link', { name: 'Next', exact: true }).click(); await page.waitForURL(url => url.searchParams.get('offset') === '25');
  let panel = await open(); await fill(panel, 'Created on second page'); const result = await save(panel); await page.getByRole('link', { name: 'Show the saved rule', exact: true }).click(); await row(result).waitFor(); assert.equal(new URL(page.url()).searchParams.get('scopeId'), c.scopeId);
  await page.goto(`${base}${path(c)}&item=${measured}`); panel = await history(measured, 'Measured execution history'); assert.equal(await panel.locator('[data-workops-automation-log]').count(), 50);
  await panel.getByRole('button', { name: 'Next executions', exact: true }).click(); await panel.getByText('Page 2', { exact: true }).waitFor(); assert.equal(await panel.locator('[data-workops-automation-log]').count(), 2);
  await panel.getByRole('button', { name: 'Previous executions', exact: true }).click(); await panel.getByText('Page 1', { exact: true }).waitFor(); await panel.getByRole('button', { name: 'Close', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
  await fixture.access(c, 'VIEW', true); await reader.goto(`${base}${path(c)}&item=${measured}`); panel = await history(measured, 'Measured execution history', reader); await panel.locator('[data-workops-automation-log]').first().waitFor(); await fixture.access(c, 'VIEW', false); await panel.getByRole('button', { name: 'Refresh history', exact: true }).click();
  await panel.getByText('Execution history is unavailable. Refresh to try again.', { exact: true }).waitFor(); assert.equal(await panel.getByText('No executions recorded yet.', { exact: true }).count(), 0); assert.equal(await panel.locator('[data-workops-automation-log]').count(), 0);
  await fixture.access(c, 'VIEW', true); await panel.getByRole('button', { name: 'Refresh history', exact: true }).click(); await panel.locator('[data-workops-automation-log]').first().waitFor(); await panel.getByRole('button', { name: 'Close', exact: true }).click(); await panel.waitFor({ state: 'hidden' }); await fixture.access(c, 'VIEW', false);

  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 1000 }); await page.goto(`${base}${path(c)}&item=${c.id}`);
    await row(c.id).waitFor(); assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)); await page.screenshot({ path: resolve(reports, `rules-${appearance}-${width}.png`), fullPage: true });
    panel = await edit(c.id, c.name); await panel.getByRole('button', { name: 'Save rule', exact: true }).scrollIntoViewIfNeeded(); await geometry(panel, 'Rule editor'); await page.screenshot({ path: resolve(reports, `editor-${appearance}-${width}.png`), fullPage: true }); await cancel(panel);
    await page.goto(`${base}${path(c)}&item=${measured}`); panel = await history(measured, 'Measured execution history'); await panel.locator('[data-workops-automation-log]').first().waitFor(); await geometry(panel, 'Execution history'); await page.screenshot({ path: resolve(reports, `history-${appearance}-${width}.png`), fullPage: true }); await panel.getByRole('button', { name: 'Close', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
  }
  assert.deepEqual(errors, []); console.log('Automation: all four scopes, actual CRUD/readback, immutable scope, shared JSON/profile controls, invalid/stale/denied recovery, versioned enable, confirmed deletion, offset results, real task-dispatch execution history, history permission retries and both palettes/four widths passed.');
} catch (error) {
  for (const tab of [page, reader]) console.error('Automation failure state', JSON.stringify(await tab.evaluate(() => ({ path: location.pathname + location.search, active: document.activeElement?.outerHTML.slice(0, 500), dialogs: [...document.querySelectorAll('dialog[open]')].map(node => ({ text: node.innerText.slice(0, 2600), name: node.querySelector('input[name=name]')?.value, conditions: node.querySelector('textarea[name=conditions]')?.value })) })).catch(String)));
  await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {}); await reader.screenshot({ path: resolve(reports, 'reader-failure.png'), fullPage: true }).catch(() => {}); throw error;
} finally { await fixture?.cleanup(); await readerContext.close(); await context.close(); await owned.close(); }
