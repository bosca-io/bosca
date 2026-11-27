import assert from 'node:assert/strict';

/** An empty bundle is editable before repositories or launch plans exist. */
export async function emptyReleaseEdits({ page, base, path, open, dialog, releaseId, read, program }) {
  await page.goto(base + path); const build = await open('Empty editable release');
  await build.getByRole('button', { name: 'Next', exact: true }).click(); await build.locator('li[aria-current=step]').filter({ hasText: 'Review' }).waitFor();
  await build.getByRole('button', { name: 'Build release', exact: true }).click(); await build.waitFor({ state: 'hidden' }); const empty = await releaseId();
  await page.getByRole('link', { name: 'Open the created release', exact: true }).click(); await page.waitForURL(url => url.pathname === `/workops/releases/${empty}`);
  await page.locator('[data-workops-release-overview]').getByText('No project versions are bundled yet. Add them with Edit release.', { exact: true }).waitFor();
  await page.getByRole('button', { name: 'Edit release', exact: true }).click(); const panel = dialog('Edit release'); await panel.waitFor();
  await panel.locator('input[name=name]').fill('Edited empty release'); await panel.getByRole('button', { name: 'Save details', exact: true }).click(); await panel.getByText('Release details saved.', { exact: true }).waitFor();
  assert.equal((await read(program)).find(value => value.id === empty).name, 'Edited empty release');
  await panel.getByRole('link', { name: 'Done', exact: true }).click(); await page.getByRole('button', { name: 'Delete release', exact: true }).click(); const deletion = dialog('Delete release'); await deletion.waitFor();
  await deletion.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await deletion.getByRole('button', { name: 'Delete release', exact: true }).click();
  await page.waitForURL(url => url.pathname === '/workops/releases' && url.searchParams.get('programId') === program); assert.equal((await read(program)).some(value => value.id === empty), false);
}
