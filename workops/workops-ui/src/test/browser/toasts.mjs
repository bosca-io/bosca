import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname), 'Use isolated Atlas data.');
const reports = resolve('build/reports/toast-browser', new URL(base).port);
await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0), context = await owned.connection.newContext(), page = await context.newPage(), errors = [];
page.on('pageerror', error => errors.push(error.message));
const message = 'Portfolio saved.';
const notice = () => page.locator('.toast-region:popover-open').filter({ hasText: message });
const edit = async () => {
  await page.locator('[data-workops-planning] tbody tr').first().getByRole('button', { name: /^Edit / }).click();
  const dialog = page.getByRole('dialog', { name: 'Edit portfolio', exact: true });
  await dialog.waitFor();
  return dialog;
};
const save = async () => {
  const dialog = await edit();
  await dialog.getByRole('button', { name: 'Save portfolio', exact: true }).click();
  await dialog.waitFor({ state: 'hidden' });
  await notice().waitFor();
  assert.equal(await notice().getByRole('status').innerText(), message);
  assert.equal(await page.locator('[role=alert]').filter({ hasText: message }).count(), 0, 'Success is not an embedded alert');
  assert.equal(await notice().evaluate(node => getComputedStyle(node).position), 'fixed');
  assert.equal(await notice().evaluate(node => node.contains(document.activeElement)), false, 'Success does not steal focus');
};
try {
  await page.goto(base + '/workops/portfolios');
  await page.locator('input[name=identifier]').fill('admin');
  await page.locator('input[name=password]').fill(process.env.ATLAS_WORKOPS_TEST_PASSWORD);
  await page.locator('button[type=submit]').click();
  await page.waitForURL(url => url.pathname !== '/login');
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]);
    await page.setViewportSize({ width, height: 900 });
    await page.goto(base + '/workops/portfolios');
    assert.equal(await page.locator('.toast-region:popover-open').count(), 0, 'Page navigation does not replay stale success');
    await save();
    const identity = await notice().getAttribute('data-bml-preserve'), sequence = Number(await notice().getAttribute('data-sequence'));
    const bounds = await notice().boundingBox();
    assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= width && bounds.y >= 0 && bounds.y + bounds.height <= 900);
    await page.screenshot({ path: resolve(reports, `${appearance}-${width}.png`), fullPage: true });
    await notice().getByRole('button', { name: 'Dismiss notification', exact: true }).click();
    await notice().waitFor({ state: 'hidden' });
    await save();
    assert.equal(await notice().getAttribute('data-bml-preserve'), identity, 'The toast retains its identity');
    assert.equal(Number(await notice().getAttribute('data-sequence')), sequence + 1, 'Identical saves issue fresh notices');
    if (appearance === 'light' && width === 390) {
      await page.getByRole('heading', { name: 'Portfolios', exact: true }).hover();
      await notice().waitFor({ state: 'hidden', timeout: 7000 });
    }
    else {
      await notice().getByRole('button', { name: 'Dismiss notification', exact: true }).click();
      await notice().waitFor({ state: 'hidden' });
    }
    const dialog = await edit();
    assert.equal(await page.locator('.toast-region:popover-open').count(), 0, 'An unrelated action does not replay dismissed success');
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' });
  }
  assert.deepEqual(errors, []);
  console.log('Shared success toasts, repeated messages, dismissal, expiry, focus, navigation and responsive palettes passed.');
} finally { await context.close(); await owned.close(); }
