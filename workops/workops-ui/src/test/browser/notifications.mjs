import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, scheme = process.env.ATLAS_WORKOPS_TEST_NOTIFICATION_SCHEME, unavailable = process.env.ATLAS_WORKOPS_TEST_UNAVAILABLE_NOTIFICATION_SCHEME;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && scheme && unavailable, 'Use the isolated Atlas notification fixture.');
const reports = resolve('build/reports/notifications-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), page = await context.newPage(), readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(), errors = [];
for (const tab of [page, reader]) { tab.on('pageerror', error => errors.push(error.message)); tab.on('console', message => { if (message.type() === 'error') errors.push(message.text()); }); }
const gql = async (query, variables = {}, tab = page) => {
  const result = await tab.evaluate(async ({ query, variables }) => (await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) })).json(), { query, variables });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
const path = (id = scheme, offset = 0) => `/workops/settings/notifications?schemeId=${id}&offset=${offset}`;
const preferencePath = '/workops/settings/notifications?tab=preferences';
const schema = (tab = page) => tab.locator('[data-bml-schema-form]');
const section = (name, tab = page) => schema(tab).getByRole('tab', { name, exact: true });
const flag = (name, tab = page) => schema(tab).getByRole('switch', { name, exact: true });
const channel = name => { const [label, event] = name.split(' · '); return schema().getByRole('group', { name: event, exact: true }).getByRole('switch', { name: label, exact: true }); };
const time = property => schema().locator(`[data-schema-property=${property}] input`);
const settled = async (tab = page) => { await tab.waitForFunction(() => !document.querySelector('[data-bml-action-pending]')); await tab.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))); };
const login = async (tab, isReader = false) => { await tab.goto(base + path()); await tab.locator('input[name=identifier]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER : 'admin'); await tab.locator('input[name=password]').fill(isReader ? process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD : process.env.ATLAS_WORKOPS_TEST_PASSWORD); await tab.locator('button[type=submit]').click(); await tab.waitForURL(url => url.pathname === '/workops/settings/notifications'); };
const read = async (tab = page) => (await gql('{workOps{notifications{myPreferences{profileId eventChannels watchAuthored watchCommented dailyDigest dndStartLocal dndEndLocal mutedTaskIds mutedProjectIds version}}}}', {}, tab)).workOps.notifications.myPreferences;
const readScheme = async () => (await gql('query($id:UUID!){workOps{notifications{scheme(id:$id){id name description eventRecipients version}}}}', { id: scheme })).workOps.notifications.scheme;
const save = async (tab = page) => { await tab.getByRole('button', { name: 'Save preferences', exact: true }).click(); await tab.getByText('Notification preferences saved.', { exact: true }).waitFor(); await settled(tab); };
const geometry = async () => { assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false); };
try {
  await login(page); await login(reader, true);
  const originalScheme = await readScheme();
  await page.getByRole('heading', { name: originalScheme.name, exact: true }).waitFor();
  assert.equal(await page.locator('[data-workops-notification-event]').count(), 25);
  assert.match(await page.locator('[data-workops-notification-event="TASK_CREATED"]').innerText(), /Assignee[\s\S]*Group/);
  assert.match(await page.locator('[data-workops-notification-event="TASK_UPDATED"]').innerText(), /Recipients unavailable/);
  assert.equal(await page.getByText('Stored <notes> & recipients', { exact: true }).count(), 1);
  assert.equal(await page.getByRole('button', { name: /Create|Edit|Delete/ }).count(), 0, 'Scheme view has no unsupported writes.');
  await page.getByRole('link', { name: 'Next', exact: true }).click(); await page.waitForURL(url => url.searchParams.get('offset') === '25');
  await page.locator('[data-workops-notification-event="LEGACY_EVENT"]').getByText('LegacyRecipient', { exact: true }).waitFor();
  assert.equal(new URL(page.url()).searchParams.get('schemeId'), scheme);
  await page.getByRole('link', { name: 'Previous', exact: true }).click(); await page.waitForURL(url => url.searchParams.get('offset') === '0');
  await page.getByRole('combobox', { name: 'Scheme', exact: true }).click(); await page.getByRole('option', { name: 'Unavailable recipient data', exact: true }).click();
  await page.waitForURL(url => url.searchParams.get('schemeId') === unavailable);
  await page.getByText('Recipient data is unavailable for this scheme.', { exact: true }).waitFor(); assert.equal(await page.locator('[data-workops-notification-event]').count(), 0);
  await page.goto(base + path('00000000-0000-0000-0000-000000000099')); await page.getByText('The selected scheme is unavailable. Choose another scheme.', { exact: true }).waitFor();
  await page.getByRole('button', { name: 'Refresh schemes', exact: true }).click(); await settled(); await page.getByText('The selected scheme is unavailable. Choose another scheme.', { exact: true }).waitFor();
  await reader.getByRole('heading', { name: originalScheme.name, exact: true }).waitFor();
  assert.equal(await reader.locator('[data-workops-notification-event]').count(), 25, 'Scheme reads retain the existing API authorization.');

  // The API continues to discard unknown channel values. Seed through that unchanged API,
  // then verify the UI preserves supported values, unknown event keys, opt-outs and mute IDs.
  const task = (await gql('query($key:String!){workOps{tasks{taskByKey(key:$key){id}}}}', { key: process.env.ATLAS_WORKOPS_TEST_TASK_KEY })).workOps.tasks.taskByKey;
  assert.ok(task?.id && process.env.ATLAS_WORKOPS_TEST_PROJECT);
  await gql('mutation($input:WorkOpsNotificationPreferenceUpdateInput!){workOps{notifications{updatePreferences(input:$input){version}}}}', { input: {
    eventChannels: { TASK_CREATED: [], LEGACY_EVENT: ['EMAIL', 'FUTURE_CHANNEL'] }, watchAuthored: true, watchCommented: true, dailyDigest: false,
    mutedTaskIds: [task.id], mutedProjectIds: [process.env.ATLAS_WORKOPS_TEST_PROJECT],
  } });
  const seeded = await read(); assert.deepEqual(seeded.eventChannels.LEGACY_EVENT, ['EMAIL']); assert.deepEqual(seeded.eventChannels.TASK_CREATED, []);
  await page.goto(base + preferencePath); await flag('Watch tasks I create').waitFor(); await flag('Watch tasks I create').uncheck();
  await section('Delivery channels').click(); assert.equal(await channel('In-app · Task created').isChecked(), false); assert.equal(await channel('Email · Task created').isChecked(), false);
  await channel('Slack · Task updated').check();
  await section('Digest and quiet hours').click(); await flag('Daily digest email').check(); await time('dndStartLocal').fill('bad-time'); await time('dndEndLocal').fill('08:00');
  await page.getByRole('button', { name: 'Save preferences', exact: true }).click(); await page.getByRole('alert').filter({ hasText: 'Enter the quiet-hours start' }).waitFor(); await settled();
  assert.equal(await time('dndStartLocal').inputValue(), 'bad-time'); assert.equal((await read()).version, seeded.version, 'Rejected local time does not write preferences.');
  await page.getByRole('button', { name: 'Refresh preferences', exact: true }).click(); await settled(); assert.equal(await time('dndStartLocal').inputValue(), 'bad-time');
  await section('Delivery channels').click(); assert.equal(await channel('Slack · Task updated').isChecked(), true);
  await section('Digest and quiet hours').click(); await time('dndStartLocal').fill('22:00'); await save();
  const saved = await read(); assert.equal(saved.watchAuthored, false); assert.equal(saved.dailyDigest, true); assert.equal(saved.dndStartLocal, '22:00'); assert.equal(saved.dndEndLocal, '08:00');
  assert.deepEqual(saved.eventChannels.TASK_CREATED, []); assert.deepEqual(saved.eventChannels.LEGACY_EVENT, ['EMAIL']); assert.ok(saved.eventChannels.TASK_UPDATED.includes('SLACK'));
  assert.deepEqual(saved.mutedTaskIds, seeded.mutedTaskIds); assert.deepEqual(saved.mutedProjectIds, seeded.mutedProjectIds);
  await page.goto(base + '/workops/inbox?tab=preferences'); await flag('Watch tasks I create').waitFor(); assert.equal(await flag('Watch tasks I create').isChecked(), false);
  await section('Delivery channels').click(); assert.equal(await channel('Slack · Task updated').isChecked(), true); assert.equal(await channel('In-app · Task created').isChecked(), false);
  await section('Watch settings').click(); await flag('Watch tasks I comment on').uncheck(); await save();
  await page.goto(base + preferencePath); await flag('Watch tasks I comment on').waitFor(); assert.equal(await flag('Watch tasks I comment on').isChecked(), false);
  await reader.goto(base + preferencePath); await flag('Watch tasks I create', reader).waitFor(); assert.equal(await flag('Watch tasks I create', reader).isChecked(), true);
  await flag('Watch tasks I create', reader).uncheck(); await save(reader); const other = await read(reader); assert.notEqual(other.profileId, saved.profileId); assert.equal(other.watchAuthored, false); assert.equal(other.watchCommented, true);
  assert.equal((await read()).watchCommented, false, 'Preferences remain caller-owned.');
  assert.deepEqual(await readScheme(), originalScheme, 'Scheme data and opaque recipient parameters remain unchanged.');
  for (const appearance of ['dark', 'light']) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]);
    for (const width of [1920, 1024, 768, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      await page.goto(base + path()); await page.getByRole('heading', { name: originalScheme.name, exact: true }).waitFor(); await geometry();
      assert.equal(await page.locator('html').getAttribute('data-theme'), appearance); await page.screenshot({ path: resolve(reports, `${appearance}-${width}-scheme.png`), fullPage: true });
      await page.goto(base + preferencePath); await schema().waitFor(); await geometry(); await page.screenshot({ path: resolve(reports, `${appearance}-${width}-watch.png`), fullPage: true });
      await section('Delivery channels').click(); await geometry(); await page.screenshot({ path: resolve(reports, `${appearance}-${width}-channels.png`), fullPage: true });
      await section('Digest and quiet hours').click(); await geometry(); await page.screenshot({ path: resolve(reports, `${appearance}-${width}-digest.png`), fullPage: true });
    }
  }
  assert.deepEqual(errors, []); console.log('Notification scheme reads, paging, caller-owned preferences, unchanged channel filtering, Inbox parity and responsive palettes passed.');
} catch (error) { await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {}); console.error(await page.locator('body').innerText().catch(() => 'Page unavailable')); throw error; }
finally { await owned.close(); }
