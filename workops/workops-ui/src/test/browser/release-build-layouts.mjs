import assert from 'node:assert/strict';
import { resolve } from 'node:path';

export async function releaseBuildLayouts({ page, context, base, path, projectLabel, versionLabel, dialog, choose, reports }) {
  const bounds = async panel => {
    const result = await panel.evaluate(node => ({ left: node.getBoundingClientRect().left, right: node.getBoundingClientRect().right, top: node.getBoundingClientRect().top, bottom: node.getBoundingClientRect().bottom, width: innerWidth, height: innerHeight, scroll: document.documentElement.scrollWidth }));
    assert.ok(result.left >= 0 && result.right <= result.width + 1 && result.top >= 0 && result.bottom <= result.height + 1 && result.scroll <= result.width + 1, JSON.stringify(result));
  };
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 1000 }); await page.goto(base + path);
    assert.equal(await page.getByRole('button', { name: 'Choose program', exact: true }).count(), 0);
    const releases = page.locator('[data-workops-releases]');
    assert.equal(await releases.getByText(/releases shown\./).count(), 0);
    const summary = releases.getByRole('navigation', { name: 'Release pages', exact: true }).locator('.page-number');
    assert.match(await summary.innerText(), /^Page 1 · \d+ releases$/);
    const presentation = await summary.evaluate(node => ({ size: getComputedStyle(node).fontSize, weight: getComputedStyle(node).fontWeight, top: node.getBoundingClientRect().top, tableBottom: node.closest('[data-workops-releases]').querySelector('table').getBoundingClientRect().bottom }));
    assert.equal(presentation.size, '12px'); assert.equal(presentation.weight, '400'); assert.ok(presentation.top >= presentation.tableBottom);
    await bounds(page.locator('.program-choice'));
    await page.screenshot({ path: resolve(reports, `program-choice-${appearance}-${width}.png`), fullPage: true });
    await page.getByRole('button', { name: 'Build release', exact: true }).click(); const panel = dialog('Build a release'); await panel.waitFor();
    await panel.locator('input[name=name]').fill('Layout release draft'); await panel.locator('textarea[name=description]').fill('A retained release and project bundle'); await panel.locator('input[name=date]').fill('2027-01-15');
    await bounds(panel); await page.screenshot({ path: resolve(reports, `details-${appearance}-${width}.png`), fullPage: true });
    await panel.getByRole('button', { name: 'Next', exact: true }).click(); await panel.locator('[data-workops-release-picker]').waitFor();
    await choose(panel, 'Project', projectLabel);
    await panel.locator('select[name=versionId] option').filter({ hasText: versionLabel }).waitFor({ state: 'attached' });
    await choose(panel, 'Version', versionLabel); await panel.getByRole('button', { name: 'Add version', exact: true }).click();
    await panel.locator('[data-workops-release-staged]').waitFor(); await bounds(panel);
    await page.screenshot({ path: resolve(reports, `projects-${appearance}-${width}.png`), fullPage: true });
    await panel.getByRole('button', { name: 'New version', exact: true }).click(); const version = dialog('New project version'); await version.waitFor();
    await version.locator('input[name=name]').fill('Unsent layout version'); await version.locator('textarea[name=description]').fill('A separate version draft'); await version.locator('input[name=startDate]').fill('2026-12-01'); await version.locator('input[name=releaseDate]').fill('2026-12-15');
    await version.getByRole('button', { name: 'Create version', exact: true }).scrollIntoViewIfNeeded(); await bounds(version);
    await page.screenshot({ path: resolve(reports, `version-${appearance}-${width}.png`), fullPage: true });
    await version.getByRole('button', { name: 'Cancel', exact: true }).click(); await version.waitFor({ state: 'hidden' });
    await panel.getByRole('button', { name: 'Next', exact: true }).click(); await panel.locator('[data-workops-release-review]').waitFor();
    await panel.getByRole('button', { name: 'Build release', exact: true }).scrollIntoViewIfNeeded(); await bounds(panel);
    await page.screenshot({ path: resolve(reports, `review-${appearance}-${width}.png`), fullPage: true });
    await panel.getByRole('button', { name: 'Back', exact: true }).click(); await panel.locator('[data-workops-release-picker]').waitFor(); await panel.getByRole('button', { name: 'Back', exact: true }).click(); await panel.locator('input[name=name]').waitFor();
    assert.equal(await panel.locator('input[name=name]').inputValue(), 'Layout release draft'); assert.equal(await panel.locator('input[name=date]').inputValue(), '2027-01-15');
    await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
  }
}
