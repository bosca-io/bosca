import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, html = process.env.ATLAS_WORKOPS_TEST_HTML_TASK, markdown = process.env.ATLAS_WORKOPS_TEST_MARKDOWN_TASK, empty = process.env.ATLAS_WORKOPS_TEST_EMPTY_TASK;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && html && markdown && empty, 'Use the isolated task description fixture.');
const reports = resolve('build/reports/task-descriptions-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0), context = owned.connection.contexts()[0], page = context.pages()[0], errors = [];
page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
const gql = async (query, variables = {}) => { const r = await page.evaluate(async ({ query, variables }) => (await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) })).json(), { query, variables }); assert.equal(r.errors, undefined, JSON.stringify(r)); return r.data; };
const read = async id => (await gql('query($id:UUID!){workOps{tasks{task(id:$id){id key summary dueDate descriptionMarkdown descriptionHtml metadataId version}}}}', { id })).workOps.tasks.task;
const path = id => `/workops/tasks/${id}`;
const surface = () => page.locator('[data-workops-task-detail] [data-bml-html-content]');
const source = () => page.locator('[data-bml-source-view][data-label="Task description"]');
const dialog = () => page.getByRole('dialog', { name: 'Edit task', exact: true });
const settled = () => page.waitForFunction(() => !document.querySelector('[data-bml-action-pending]'));
const due = () => page.locator('dt', { hasText: /^Due date$/ }).locator('+ dd time');
const checkDueDate = async kind => {
  if (kind === 'empty') { assert.equal(await due().count(), 0); return; }
  assert.equal(await due().evaluate(element => element.hasAttribute('data-overdue')), kind === 'html');
  assert.equal(Date.parse(await due().getAttribute('datetime')), Date.parse(kind === 'html' ? '2000-01-01T00:00:00Z' : '2099-12-03T00:00:00Z'));
  const colors = await due().evaluate(element => {
    const sample = document.createElement('span'); sample.style.color = 'var(--err)'; element.parentElement.append(sample);
    const actual = getComputedStyle(element).color, error = getComputedStyle(sample).color; sample.remove(); return { actual, error };
  });
  assert.equal(colors.actual === colors.error, kind === 'html', JSON.stringify(colors));
};
try {
  await page.goto(base + path(html)); await page.locator('input[name=identifier]').fill('admin'); await page.locator('input[name=password]').fill(process.env.ATLAS_WORKOPS_TEST_PASSWORD); await page.locator('button[type=submit]').click(); await page.waitForURL(u => u.pathname === path(html));
  const initial = await read(html); await surface().getByRole('heading', { name: 'Stored task description', exact: true }).waitFor(); assert.equal(await source().count(), 0);
  assert.equal(await surface().locator('strong').textContent(), 'prepared description'); assert.equal(await surface().locator('em').textContent(), 'formatting'); await surface().getByText('Image unavailable: Stored illustration', { exact: true }).waitFor();
  await page.getByRole('button', { name: 'Add document', exact: true }).click(); await page.getByText('Document added.', { exact: true }).waitFor(); await page.locator('[data-workops-document] [data-document-ready]').waitFor();
  const withDocument = await read(html); assert.ok(withDocument.metadataId);
  const document = async () => (await gql('query($id:UUID!){content{metadata(id:$id,version:1){document{content}}}}', { id: withDocument.metadataId })).content.metadata.document.content;
  const beforeDocument = await document();
  await page.getByRole('button', { name: 'Edit task', exact: true }).click(); await dialog().waitFor(); await dialog().locator('input[name=summary]').fill('Stored HTML retained after editing');
  await dialog().locator('[data-bml-code-editor] .cm-content').fill('Independent Markdown edit'); await dialog().getByRole('button', { name: 'Save task', exact: true }).click(); await dialog().waitFor({ state: 'hidden' }); await page.getByText('Task saved.', { exact: true }).waitFor();
  const saved = await read(html); assert.equal(saved.descriptionHtml, initial.descriptionHtml); assert.equal(saved.descriptionMarkdown, 'Independent Markdown edit'); assert.equal(saved.metadataId, withDocument.metadataId); assert.deepEqual(await document(), beforeDocument); assert.equal(saved.dueDate, initial.dueDate);
  await page.reload(); await surface().getByRole('heading', { name: 'Stored task description', exact: true }).waitFor(); assert.equal(await source().count(), 0);
  await page.goto(base + path(markdown)); await source().locator('.cm-content').waitFor(); assert.ok((await source().innerText()).includes('## Markdown fallback')); assert.equal(await surface().count(), 0);
  await page.goto(base + path(empty)); await page.getByText('No description.', { exact: true }).waitFor(); assert.equal(await source().count(), 0); assert.equal(await surface().count(), 0);
  assert.equal(await page.locator('dt', { hasText: /^Start date$/ }).count(), 0);
  for (const appearance of ['dark', 'light']) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]);
    for (const width of [1920, 1024, 768, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      for (const [kind, id] of [['html', html], ['markdown', markdown], ['empty', empty]]) {
        await page.goto(base + path(id)); await page.locator('[data-workops-task-detail]').waitFor();
        if (kind === 'html') {
          await surface().getByText('Image unavailable: Stored illustration', { exact: true }).waitFor();
          const colors = await surface().evaluate(r => [getComputedStyle(r).color, getComputedStyle(r.querySelector('td')).color, getComputedStyle(r.querySelector('td')).borderTopStyle]); assert.equal(colors[0], colors[1]); assert.equal(colors[2], 'solid');
        } else if (kind === 'markdown') await source().locator('.cm-content').waitFor(); else await page.getByText('No description.', { exact: true }).waitFor();
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false); assert.equal(await page.locator('html').getAttribute('data-theme'), appearance);
        await checkDueDate(kind);
        await page.screenshot({ path: resolve(reports, `${appearance}-${width}-${kind}.png`), fullPage: true });
        if (kind === 'html') { await due().scrollIntoViewIfNeeded(); await page.screenshot({ path: resolve(reports, `${appearance}-${width}-overdue.png`), fullPage: true }); }
      }
    }
  }
  await settled(); assert.deepEqual(errors, []); console.log('Stored task HTML, Markdown/empty fallback, unchanged API fields and independent Content document passed in both palettes and four widths.');
} catch (e) { await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {}); console.error(await page.locator('body').innerText().catch(() => 'Page unavailable')); throw e; }
finally { await owned.close(); }
