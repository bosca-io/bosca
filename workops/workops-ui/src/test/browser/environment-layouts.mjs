import assert from 'node:assert/strict';
import { resolve } from 'node:path';

/** Actual create/edit/grant and delete dialogs use the shared controls at every acceptance width. */
export async function environmentLayouts({ page, context, base, hub, environment, environmentName, type, types, dialog, choose, reports }) {
  const geometry = async (element, label) => {
    const bounds = await element.evaluate(node => ({ left: node.getBoundingClientRect().left, right: node.getBoundingClientRect().right, top: node.getBoundingClientRect().top, bottom: node.getBoundingClientRect().bottom, width: innerWidth, height: innerHeight, scroll: document.documentElement.scrollWidth }));
    assert.ok(bounds.left >= 0 && bounds.right <= bounds.width + 1 && bounds.scroll <= bounds.width + 1, label + JSON.stringify(bounds));
    if (label.includes('dialog')) assert.ok(bounds.top >= 0 && bounds.bottom <= bounds.height + 1, label + JSON.stringify(bounds));
  };
  const headerControls = async (createName, refreshName, link) => {
    const header = page.locator('.page-heading').first();
    const controls = [header.getByRole('button', { name: createName, exact: true }), header.getByRole('button', { name: refreshName, exact: true }), link];
    const styles = [];
    for (const control of controls) styles.push(await control.evaluate(node => ({ height: node.getBoundingClientRect().height, radius: getComputedStyle(node).borderRadius, font: getComputedStyle(node).fontSize })));
    assert.deepEqual(styles.map(style => style.height), [32, 32, 32], 'Header controls share one height');
    assert.ok(styles.every(style => style.radius === styles[0].radius), 'Header controls share one corner shape');
    assert.equal(styles[2].font, styles[0].font, 'Navigation and primary action use matching typography');
  };
  const rowActions = async row => {
    const controls = row.locator('td').last().locator('.form-actions');
    assert.ok(Number.parseFloat(await controls.evaluate(node => getComputedStyle(node).columnGap)) >= 8, 'Row actions use shared spacing');
    for (const verb of ['Edit', 'Delete']) {
      const button = row.getByRole('button', { name: new RegExp(`^${verb} `) });
      assert.equal(await button.evaluate(node => node.classList.contains('icon-only')), true);
      assert.equal(await button.locator('.button-content').innerText(), '');
      assert.equal(await button.locator('.button-icon svg').count(), 1);
      assert.equal(await button.getAttribute('data-tooltip-content'), await button.getAttribute('aria-label'));
    }
  };
  const cancel = async panel => { await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await panel.waitFor({ state: 'hidden' }); await panel.page().waitForFunction(() => !document.querySelector('[data-bml-action-pending]')); };
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 1000 });
    await page.goto(base + hub); const row = page.locator(`[data-workops-environment="${environment}"]`); await row.waitFor();
    await geometry(page.locator('[data-workops-environments]'), 'Environment table');
    await rowActions(row);
    assert.equal(await page.getByText('Global environment types define the stages each program can use.', { exact: true }).count(), 0);
    const typesLink = page.getByRole('link', { name: 'Environment types', exact: true });
    assert.equal(await typesLink.locator('xpath=ancestor::header[contains(@class,"page-heading")]').count(), 1);
    assert.equal(await page.getByRole('heading', { name: 'Promotion environments', exact: true }).count(), 0);
    await headerControls('New environment', 'Refresh environments', typesLink);
    await page.screenshot({ path: resolve(reports, `environments-${appearance}-${width}.png`), fullPage: true });
    await page.getByRole('button', { name: 'New environment', exact: true }).click(); let panel = dialog('New environment'); await panel.waitFor();
    await panel.locator('input[name=name]').fill('Layout environment draft'); assert.equal(await panel.locator('input[name=key]').inputValue(), 'layout-environment-draft');
    await panel.locator('input[name=key]').fill('layout-key'); await choose(panel, 'Environment type', type.name);
    await panel.locator('textarea[name=description]').fill('A retained environment draft'); await panel.getByRole('button', { name: 'Save environment', exact: true }).scrollIntoViewIfNeeded();
    await geometry(panel, 'Create environment dialog'); await page.screenshot({ path: resolve(reports, `environment-create-${appearance}-${width}.png`), fullPage: true }); await cancel(panel);
    await row.getByRole('button', { name: `Edit ${environmentName}`, exact: true }).click(); panel = dialog('Edit environment'); await panel.waitFor();
    await panel.locator('input[name=name]').fill('Unsent environment layout'); await panel.getByRole('button', { name: 'Save environment', exact: true }).scrollIntoViewIfNeeded();
    await geometry(panel, 'Edit environment dialog'); await page.screenshot({ path: resolve(reports, `environment-edit-${appearance}-${width}.png`), fullPage: true }); await cancel(panel);
    await row.getByRole('button', { name: `Delete ${environmentName}`, exact: true }).click(); panel = dialog('Delete environment'); await panel.waitFor(); await geometry(panel, 'Delete environment dialog'); await cancel(panel);
    await page.goto(base + types.path); await types.row(type.id).waitFor();
    assert.equal(await page.getByRole('heading', { name: 'Global environment types', exact: true }).count(), 0);
    assert.equal(await page.locator('table caption').filter({ hasText: 'Global environment types' }).count(), 1);
    await geometry(page.locator('[data-workops-environment-types]'), 'Type table');
    await rowActions(types.row(type.id));
    await headerControls('New environment type', 'Refresh environment types', page.getByRole('link', { name: 'Program environments', exact: true }));
    await page.getByRole('button', { name: 'New environment type', exact: true }).click(); panel = dialog('New environment type'); await panel.waitFor();
    await panel.locator('input[name=name]').fill('Layout type draft'); await panel.locator('input[name=order]').fill('99'); await panel.locator('textarea[name=description]').fill('An unsent catalog draft');
    await panel.getByRole('button', { name: 'Save environment type', exact: true }).scrollIntoViewIfNeeded(); await geometry(panel, 'Create type dialog');
    await page.screenshot({ path: resolve(reports, `type-create-${appearance}-${width}.png`), fullPage: true }); await cancel(panel);
    await types.row(type.id).getByRole('button', { name: `Edit ${type.name}`, exact: true }).click(); panel = dialog('Edit environment type'); await panel.waitFor();
    await panel.locator('textarea[name=description]').fill('Unsent type edits'); await geometry(panel, 'Edit type dialog'); await cancel(panel);
    await types.row(type.id).getByRole('button', { name: `Delete ${type.name}`, exact: true }).click(); panel = dialog('Delete environment type'); await panel.waitFor(); await geometry(panel, 'Delete type dialog'); await cancel(panel);
  }
}
