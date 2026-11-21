import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, id = process.env.ATLAS_WORKOPS_TEST_HISTORY_TASK, legacy = 'REQ UI-REQ-27';
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && id, 'Use the isolated task history fixture.');
const reports = resolve('build/reports/task-history-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0), context = owned.connection.contexts()[0], page = context.pages()[0], errors = [];
page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
const read = async () => {
  const result = await page.evaluate(async id => (await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query: 'query($id:UUID!){workOps{tasks{task(id:$id){summary history(offset:0,limit:25){changes{fieldKey fromValue toValue}}}}}}', variables: { id } }) })).json(), id);
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data.workOps.tasks.task;
};
const history = () => page.locator('.workops-task-entry p').filter({ hasText: 'Requirement → Authored task title' });
try {
  await page.goto(`${base}/workops/tasks/${id}`); await page.locator('input[name=identifier]').fill('admin'); await page.locator('input[name=password]').fill(process.env.ATLAS_WORKOPS_TEST_PASSWORD); await page.locator('button[type=submit]').click(); await page.waitForURL(u => u.pathname === `/workops/tasks/${id}`);
  await page.getByRole('heading', { name: 'Requirement', exact: true }).waitFor(); assert.equal((await read()).summary, legacy);
  await page.getByRole('button', { name: 'Edit task', exact: true }).click(); const dialog = page.getByRole('dialog', { name: 'Edit task', exact: true }); await dialog.waitFor(); assert.equal(await dialog.locator('input[name=summary]').inputValue(), 'Requirement');
  await dialog.locator('input[name=summary]').fill('Authored task title'); await dialog.getByRole('button', { name: 'Save task', exact: true }).click(); await dialog.waitFor({ state: 'hidden' }); await page.getByText('Task saved.', { exact: true }).waitFor();
  const stored = await read(), change = stored.history.flatMap(e => e.changes).find(c => c.fieldKey === 'summary');
  assert.equal(stored.summary, 'Authored task title'); assert.equal(change.fromValue, legacy); assert.equal(change.toValue, 'Authored task title');
  for (const appearance of ['dark', 'light']) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]);
    for (const width of [1920, 1024, 768, 390]) {
      await page.setViewportSize({ width, height: 1000 }); await page.goto(`${base}/workops/tasks/${id}?tab=history`); await history().waitFor(); await history().scrollIntoViewIfNeeded();
      const text = await page.locator('body').innerText(); assert.equal(text.includes('UI-REQ-27'), false); assert.ok(text.includes('Authored task title'));
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false); assert.equal(await page.locator('html').getAttribute('data-theme'), appearance);
      await page.screenshot({ path: resolve(reports, `${appearance}-${width}-history.png`), fullPage: true });
    }
  }
  assert.deepEqual(errors, []); console.log('Task summary history hides generated document tracking keys while preserving API history and authored names across palettes and widths.');
} catch (e) { await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {}); console.error(await page.locator('body').innerText().catch(() => 'Page unavailable')); throw e; }
finally { await owned.close(); }
