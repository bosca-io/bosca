import { compactResult } from './presentation-checks.mjs';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, measured = process.env.ATLAS_WORKOPS_TEST_SLA_POLICY;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && process.env.ATLAS_WORKOPS_TEST_PROJECT && measured, 'Use the isolated Atlas SLA fixture.');
const reports = resolve('build/reports/sla-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), page = await context.newPage(), readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(), errors = [];
for (const tab of [page, reader]) { tab.on('pageerror', error => errors.push(error.message)); tab.on('console', message => { if (message.type() === 'error') errors.push(message.text()); }); }
const gql = async (query, variables = {}, tab = page) => {
  const result = await tab.evaluate(async ({ query, variables }) => (await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) })).json(), { query, variables });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
const path = kind => `/workops/settings/sla?tab=${kind}`;
const label = kind => kind === 'calendars' ? 'working calendar' : 'SLA policy';
const dialog = (title, tab = page) => tab.getByRole('dialog', { name: title, exact: true });
const row = (kind, id, tab = page) => tab.locator(`[data-workops-sla-${kind === 'calendars' ? 'calendar' : 'policy'}="${id}"]`);
const weeklyHours = { MONDAY: [{ startLocal: '09:30', endLocal: '12:00' }, { startLocal: '13:00', endLocal: '24:00', label: '<late> & urgent' }], FRIDAY: [] }, holidays = ['2026-12-25', '2027-01-01'];
const login = async (tab, isReader = false) => {
  await tab.goto(base + path('policies')); await tab.locator('input[name=identifier]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER : 'admin');
  await tab.locator('input[name=password]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD : process.env.ATLAS_WORKOPS_TEST_PASSWORD);
  await tab.locator('button[type=submit]').click(); await tab.waitForURL(url => url.pathname === '/workops/settings/sla');
};
const read = async () => (await gql('{workOps{sla{slaPolicies{id name description goals{id name targetMinutes atRiskAtPercent startConditions stopConditions pauseConditions displayOrder}} workingCalendars{id name description timeZone weeklyHours holidays}}}}')).workOps.sla;
const values = (kind, data) => data[kind === 'calendars' ? 'workingCalendars' : 'slaPolicies'];
const ready = async panel => { await panel.locator('[data-bml-json-editor][data-json-ready]').nth(1).waitFor(); };
const settled = async panel => { await panel.page().waitForFunction(() => !document.querySelector('dialog[open] form[data-bml-action-pending]')); await panel.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))); };
const setJson = async (panel, name, text) => {
  await settled(panel); await panel.locator(`[data-bml-json-editor][data-name="${name}"] .cm-content[contenteditable=true]`).fill(text);
  await panel.page().waitForFunction(([name, text]) => document.querySelector(`dialog[open] textarea[name="${name}"]`)?.value === text, [name, text]);
};
const open = async (kind, tab = page) => { await tab.getByRole('button', { name: `New ${label(kind)}`, exact: true }).click(); const panel = dialog(`New ${label(kind)}`, tab); await panel.waitFor(); if (kind === 'calendars') await ready(panel); return panel; };
const fill = async (kind, panel, name, notes = ' SLA <notes> & details ') => {
  await panel.locator('input[name=name]').click(); await panel.locator('input[name=name]').fill(name); await panel.locator('textarea[name=description]').fill(notes);
  if (kind === 'calendars') { await panel.locator('input[name=timeZone]').fill('Pacific/Auckland'); await setJson(panel, 'weeklyHours', JSON.stringify(weeklyHours)); await setJson(panel, 'holidays', JSON.stringify(holidays)); }
};
const save = async (kind, panel) => { await panel.getByRole('button', { name: `Create ${label(kind)}`, exact: true }).click(); await panel.waitFor({ state: 'hidden' }); const link = panel.page().getByRole('link', { name: `Show the created ${label(kind)}`, exact: true }); await link.waitFor(); await compactResult(link); return new URL(await link.getAttribute('href'), base).searchParams.get('item'); };
const reject = async (kind, panel) => { await panel.getByRole('button', { name: `Create ${label(kind)}`, exact: true }).click(); await panel.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); await settled(panel); };
const cancel = async panel => { await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await panel.waitFor({ state: 'hidden' }); };
const detail = async (kind, id, name) => { await row(kind, id).getByRole('button', { name: `View ${name}`, exact: true }).click(); const panel = dialog(kind === 'calendars' ? 'Working calendar detail' : 'SLA policy detail'); await panel.waitFor(); return panel; };
const close = async panel => { await panel.getByRole('button', { name: 'Close', exact: true }).click(); await panel.waitFor({ state: 'hidden' }); };
const geometry = async (element, modal = false) => {
  const r = await element.evaluate(node => { const r = node.getBoundingClientRect(); return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: innerWidth, height: innerHeight, scroll: document.documentElement.scrollWidth }; });
  assert.ok(r.left >= 0 && r.right <= r.width + 1 && r.scroll <= r.width + 1, JSON.stringify(r)); if (modal) assert.ok(r.top >= 0 && r.bottom <= r.height + 1, JSON.stringify(r));
};
let readerId, adminGroup, granted = false;
const permission = async add => { await gql(`mutation($group:UUID!,$principal:UUID!){security{${add ? 'add' : 'remove'}PrincipalGroup(groupId:$group,principalId:$principal)}}`, { group: adminGroup, principal: readerId }); granted = add; };
const ids = {}, names = { policies: 'Atlas SLA policy', calendars: 'Atlas working calendar' };
try {
  await login(page); await login(reader, true); await reader.getByText('SLA settings have not been loaded. Refresh to try again.', { exact: true }).waitFor(); assert.equal(await reader.locator('[data-workops-sla-policy]').count(), 0);
  readerId = (await gql('{security{principals{current{id}}}}', {}, reader)).security.principals.current.id;
  adminGroup = (await gql('{security{groups{find(nameOrDescription:"administrators",offset:0,limit:100,type:SYSTEM){id name}}}}')).security.groups.find.find(value => value.name === 'administrators')?.id; assert.ok(adminGroup);
  for (const kind of ['policies', 'calendars']) {
    await page.goto(base + path(kind)); let panel = await open(kind); await fill(kind, panel, ` ${names[kind]} `);
    if (kind === 'calendars') {
      await setJson(panel, 'weeklyHours', '{ invalid hours'); await reject(kind, panel); assert.equal(await panel.locator('textarea[name=weeklyHours]').inputValue(), '{ invalid hours');
      await setJson(panel, 'weeklyHours', '[]'); await reject(kind, panel); await panel.getByRole('alert').filter({ hasText: 'Weekly hours must be a JSON object.' }).waitFor();
      await setJson(panel, 'weeklyHours', JSON.stringify(weeklyHours)); await setJson(panel, 'holidays', '{}'); await reject(kind, panel); await panel.getByRole('alert').filter({ hasText: 'Holidays must be a JSON array.' }).waitFor();
      await setJson(panel, 'holidays', JSON.stringify(holidays)); await panel.locator('input[name=timeZone]').click(); await panel.locator('input[name=timeZone]').fill('Unknown/Place'); await reject(kind, panel); assert.equal(await panel.locator('input[name=timeZone]').inputValue(), 'Unknown/Place');
      await panel.getByRole('button', { name: 'Retry loading SLA settings', exact: true }).click(); await settled(panel); await panel.getByRole('alert').waitFor({ state: 'hidden' });
      assert.equal(await panel.locator('input[name=timeZone]').inputValue(), 'Unknown/Place');
      assert.deepEqual(JSON.parse(await panel.locator('textarea[name=weeklyHours]').inputValue()), weeklyHours); assert.deepEqual(JSON.parse(await panel.locator('textarea[name=holidays]').inputValue()), holidays);
      await panel.locator('input[name=timeZone]').click(); await panel.locator('input[name=timeZone]').fill('Pacific/Auckland'); assert.equal(await panel.locator('input[name=timeZone]').inputValue(), 'Pacific/Auckland');
    }
    ids[kind] = await save(kind, panel); const stored = values(kind, await read()).find(value => value.id === ids[kind]); assert.equal(stored.name, names[kind]); assert.equal(stored.description, 'SLA <notes> & details');
    if (kind === 'calendars') { assert.equal(stored.timeZone, 'Pacific/Auckland'); assert.deepEqual(stored.weeklyHours, weeklyHours); assert.deepEqual(stored.holidays, holidays); }
    else { assert.deepEqual(stored.goals, []); panel = await detail(kind, stored.id, stored.name); await panel.getByText('No goals defined for this policy.', { exact: true }).waitFor(); await close(panel); }

    await permission(true); await reader.goto(base + path(kind)); panel = await open(kind, reader); await fill(kind, panel, `Reader ${label(kind)}`, kind === 'policies' ? '' : ' Reader notes '); await permission(false);
    await reject(kind, panel); assert.equal(await panel.locator('input[name=name]').inputValue(), `Reader ${label(kind)}`);
    if (kind === 'calendars') { assert.deepEqual(JSON.parse(await panel.locator('textarea[name=weeklyHours]').inputValue()), weeklyHours); assert.deepEqual(JSON.parse(await panel.locator('textarea[name=holidays]').inputValue()), holidays); }
    await permission(true); const created = await save(kind, panel); const actual = values(kind, await read()).find(value => value.id === created); assert.equal(actual.name, `Reader ${label(kind)}`); if (kind === 'policies') assert.equal(actual.description, null);
    await permission(false); await reader.getByRole('button', { name: 'Refresh SLA settings', exact: true }).click(); await reader.getByText('SLA settings have not been loaded. Refresh to try again.', { exact: true }).waitFor();
    assert.equal(await reader.locator('[data-workops-sla-policy], [data-workops-sla-calendar]').count(), 0); assert.equal(await reader.getByText(kind === 'calendars' ? 'No working calendars on this page.' : 'No SLA policies on this page.', { exact: true }).count(), 0);

    for (let index = 0; index < 27; index++) {
      if (kind === 'policies') await gql('mutation($name:String!){workOps{sla{createSlaPolicy(name:$name){id}}}}', { name: `Paged SLA policy ${index}` });
      else await gql('mutation($input:WorkOpsWorkingCalendarInput!){workOps{sla{createWorkingCalendar(input:$input){id}}}}', { input: { name: `Paged working calendar ${index}`, timeZone: 'UTC', weeklyHours: {}, holidays: [] } });
    }
    await page.goto(base + path(kind)); assert.equal(await page.locator(`[data-workops-sla-${kind === 'calendars' ? 'calendar' : 'policy'}]`).count(), 25);
    await page.getByRole('navigation', { name: `${kind === 'calendars' ? 'Working calendars' : 'SLA policies'} pages`, exact: true }).getByRole('link', { name: 'Next', exact: true }).click(); await page.waitForURL(url => url.searchParams.get('offset') === '25');
    panel = await open(kind); await fill(kind, panel, `Created on second ${kind} page`); const id = await save(kind, panel); await page.getByRole('link', { name: `Show the created ${label(kind)}`, exact: true }).click(); await row(kind, id).waitFor(); assert.equal(new URL(page.url()).searchParams.get('tab'), kind);
  }
  const policy = (await read()).slaPolicies.find(value => value.id === measured); assert.deepEqual(policy.goals.map(goal => goal.name), ['First response', 'Resolution']);
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 1000 });
    for (const kind of ['policies', 'calendars']) {
      const id = kind === 'policies' ? measured : ids.calendars, name = kind === 'policies' ? policy.name : names.calendars;
      await page.goto(`${base}${path(kind)}&item=${id}`); await row(kind, id).waitFor(); await geometry(page.locator('[data-workops-sla]')); await page.screenshot({ path: resolve(reports, `${kind}-list-${appearance}-${width}.png`), fullPage: true });
      let panel = await open(kind); if (kind === 'calendars') { assert.equal(await panel.locator('input[name=timeZone]').inputValue(), 'America/New_York'); assert.deepEqual(JSON.parse(await panel.locator('textarea[name=holidays]').inputValue()), []); }
      await panel.getByRole('button', { name: `Create ${label(kind)}`, exact: true }).scrollIntoViewIfNeeded(); await geometry(panel, true); await page.screenshot({ path: resolve(reports, `${kind}-create-${appearance}-${width}.png`), fullPage: true }); await cancel(panel);
      panel = await detail(kind, id, name);
      if (kind === 'policies') {
        assert.deepEqual(await panel.locator('[data-workops-sla-goal]').evaluateAll(nodes => nodes.map(node => node.dataset.workopsSlaGoal)), policy.goals.map(goal => goal.id));
        await panel.getByText('45m', { exact: true }).waitFor(); await panel.getByText('2h 5m', { exact: true }).waitFor(); await panel.getByText('At risk: 75%', { exact: true }).waitFor(); await panel.getByText('At risk: 90%', { exact: true }).waitFor();
        const sources = panel.locator('[data-bml-source-view][data-source-ready]'); await sources.nth(4).waitFor(); assert.equal(await sources.count(), 5);
        for (const source of await sources.all()) { assert.equal(await source.locator('.cm-content').getAttribute('contenteditable'), 'false'); assert.ok(['open', 'done', 'paused'].includes((await source.locator('.cm-content').innerText()).trim())); }
      } else {
        await ready(panel); await panel.getByText('Time zone: Pacific/Auckland', { exact: true }).waitFor();
        for (const [field, expected] of [['storedWeeklyHours', weeklyHours], ['storedHolidays', holidays]]) { const native = panel.locator(`textarea[name="${field}"]`); assert.deepEqual(JSON.parse(await native.inputValue()), expected); assert.equal(await native.evaluate(node => node.readOnly), true); }
      }
      await panel.getByRole('button', { name: 'Close', exact: true }).scrollIntoViewIfNeeded(); await geometry(panel, true); await page.screenshot({ path: resolve(reports, `${kind}-detail-${appearance}-${width}.png`), fullPage: true }); await close(panel);
    }
  }
  assert.deepEqual(errors, []); console.log('SLA: actual policy/calendar creation and structured JSON readback, invalid syntax/shapes/timezone recovery, retained reload/denied drafts, administrator access changes, empty versus unavailable reads, offset results, ordered goals/durations/risk/conditions, read-only stored calendars and both palettes/four widths passed.');
} catch (error) {
  for (const tab of [page, reader]) console.error('SLA failure state', JSON.stringify(await tab.evaluate(() => ({ path: location.pathname + location.search, dialogs: [...document.querySelectorAll('dialog[open]')].map(node => ({ text: node.innerText.slice(0, 2400), name: node.querySelector('input[name=name]')?.value, timeZone: node.querySelector('input[name=timeZone]')?.value })) })).catch(String)));
  await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {}); await reader.screenshot({ path: resolve(reports, 'reader-failure.png'), fullPage: true }).catch(() => {}); throw error;
} finally { if (granted) await permission(false); await readerContext.close(); await context.close(); await owned.close(); }
