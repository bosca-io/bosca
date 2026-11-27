import assert from 'node:assert/strict';
import { resolve } from 'node:path';

export async function releaseCiLayouts({ page, context, base, path, reports, dialog, choose, button, scope }) {
  const closed = async panel => {
    await panel.waitFor({ state: 'hidden' });
    await scope(page).locator('[data-bml-action-pending]').waitFor({ state: 'detached' });
    await page.waitForFunction(() => document.querySelector('[data-workops-release-ci]')?.contains(document.activeElement));
  };
  const bounds = async panel => {
    const box = await panel.boundingBox(), size = page.viewportSize();
    assert.ok(box && box.x >= 0 && box.x + box.width <= size.width + 1 && box.y >= 0 && box.y + box.height <= size.height + 1, JSON.stringify(box));
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  };
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 1000 }); await page.goto(base + path);
    assert.ok(await scope(page).getByRole('link', { name: 'Logs and details', exact: true }).isVisible());
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await scope(page).locator('.release-plan-flow').first().scrollIntoViewIfNeeded();
    await page.screenshot({ path: resolve(reports, `plans-${appearance}-${width}.png`), fullPage: true });
    const run = scope(page).locator('[data-workops-release-native-run]'); await run.scrollIntoViewIfNeeded();
    for (const summary of [run.locator('.ci-run-summary'), scope(page).locator('.release-plan-summary').first()]) assert.deepEqual(await summary.evaluate(node => [getComputedStyle(node).display, getComputedStyle(node).gap]), ['flex', '8px'], 'Run and plan metadata retain space around status badges');
    assert.ok(await run.evaluate(node => node.scrollWidth <= node.clientWidth + 1));
    await page.screenshot({ path: resolve(reports, `run-${appearance}-${width}.png`), fullPage: true });
    await button(page, 'Start release').click(); let panel = dialog('Start release', page); await panel.waitFor();
    await panel.getByRole('textbox', { name: /^message/ }).fill('Unsent layout release'); await choose(panel, 'region', 'east', page);
    await panel.getByRole('button', { name: 'Start release', exact: true }).scrollIntoViewIfNeeded(); await bounds(panel); await page.screenshot({ path: resolve(reports, `start-${appearance}-${width}.png`), fullPage: true });
    await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await closed(panel);
    await button(page, 'Promote').click(); panel = dialog('Promote to production', page); await panel.waitFor();
    await panel.getByRole('checkbox', { name: 'Allow a version downgrade', exact: true }).check(); await bounds(panel); await page.screenshot({ path: resolve(reports, `promote-${appearance}-${width}.png`), fullPage: true });
    await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await closed(panel);
    await button(page, 'Roll back environment').click(); panel = dialog('Roll back production', page); await panel.waitFor(); await panel.locator('input[name=revision]').fill('7');
    await bounds(panel); await page.screenshot({ path: resolve(reports, `environment-rollback-${appearance}-${width}.png`), fullPage: true });
    await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await closed(panel);
    await button(page, 'Rerun job').click(); panel = dialog('Rerun job', page); await panel.waitFor(); await bounds(panel);
    await page.screenshot({ path: resolve(reports, `rerun-${appearance}-${width}.png`), fullPage: true }); await panel.getByRole('button', { name: 'Keep current state', exact: true }).click(); await closed(panel);
    await button(page, 'Patch release').click(); panel = dialog('Create patch release', page); await panel.waitFor(); await choose(panel, /^Affected projects/, 'RCIAPP · Native release application', page); await page.keyboard.press('Escape');
    await bounds(panel); await page.screenshot({ path: resolve(reports, `patch-${appearance}-${width}.png`), fullPage: true }); await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await closed(panel);
    await button(page, 'Roll back attempt').click(); panel = dialog('Roll back release attempt', page); await panel.waitFor(); await bounds(panel); await page.screenshot({ path: resolve(reports, `rollback-${appearance}-${width}.png`), fullPage: true });
    await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await closed(panel);
  }
}
