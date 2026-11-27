import assert from 'node:assert/strict';
import { resolve } from 'node:path';

export async function releaseHeaderControls(page, width) {
  const heading = page.locator('.page-heading').first(), positions = [];
  for (const name of ['Edit release', 'Delete release', 'Refresh release overview']) {
    const control = heading.getByRole('button', { name, exact: true }), box = await control.boundingBox();
    assert.equal(box.height, 32, `${name} belongs to the page toolbar`); positions.push(box.y);
  }
  assert.equal((await heading.getByRole('button', { name: 'Delete release', exact: true }).innerText()).trim(), '');
  assert.equal(await heading.getByText('Manage permission on the release program is required to change this release.', { exact: true }).count(), 0, 'Permission guidance stays out of the toolbar');
  if (width >= 1280) assert.ok(Math.max(...positions) - Math.min(...positions) < 1, 'Header controls share one baseline for managers and viewers');
}

export async function releaseDetailEdits({ page, base, built, first, createdVersion, gql, bundles, read, program, dialog, choose, expected }) {
  await page.getByRole('link', { name: 'Open the created release', exact: true }).click();
  await page.waitForURL(url => url.pathname === `/workops/releases/${built}`); await page.locator('[data-workops-release-overview]').waitFor();
  assert.equal(await page.locator('[data-workops-release-component]').count(), 3);
  await page.getByRole('button', { name: 'Edit release', exact: true }).click(); const panel = dialog('Edit release'); await panel.waitFor();
  await panel.locator('input[name=name]').fill(' Edited in Atlas '); await panel.locator('textarea[name=description]').fill('Unsent release edit'); await panel.locator('input[name=date]').fill('2027-02-01');
  await choose(panel, 'Project', 'RAPP1 · Application one');
  await panel.getByText('Versions for: RAPP1 · Application one', { exact: true }).waitFor();
  assert.equal(await panel.locator('input[name=name]').inputValue(), ' Edited in Atlas ');
  assert.equal(await panel.locator('input[name=date]').inputValue(), '2027-02-01');
  await panel.getByRole('button', { name: 'New version', exact: true }).click(); const version = dialog('New project version'); await version.waitFor();
  await version.locator('input[name=name]').fill('4.0'); await version.locator('textarea[name=description]').fill('Created from release editing');
  await version.getByRole('button', { name: 'Create version', exact: true }).click(); await version.waitFor({ state: 'hidden' });
  const added = await panel.locator('select[name=versionId]').inputValue(); assert.ok(added);
  const newVersion = (await gql('query($id:UUID!){workOps{versions{byProject(projectId:$id){id name description}}}}', { id: first })).workOps.versions.byProject.find(value => value.id === added);
  assert.equal(newVersion.name, '4.0'); assert.equal(newVersion.description, 'Created from release editing');
  await panel.getByRole('button', { name: 'Add version', exact: true }).click(); await panel.locator(`[data-workops-release-edit-version="${added}"]`).waitFor();
  await page.locator(`[data-workops-release-component="${added}"]`).waitFor({ state: 'attached' });
  assert.equal((await bundles(built)).length, 4); assert.equal((await read(program)).find(value => value.id === built).name, 'Built in Atlas');
  assert.equal(await panel.locator('textarea[name=description]').inputValue(), 'Unsent release edit');
  await panel.locator(`[data-workops-release-edit-version="${createdVersion}"]`).getByRole('button', { name: /^Remove / }).click();
  const removal = dialog('Remove bundled version'); await removal.waitFor(); await removal.getByRole('checkbox', { name: 'Confirm removal', exact: true }).check();
  await removal.getByRole('button', { name: 'Remove version', exact: true }).click(); await removal.waitFor({ state: 'hidden' });
  await page.locator(`[data-workops-release-component="${createdVersion}"]`).waitFor({ state: 'detached' });
  assert.equal((await bundles(built)).length, 3); assert.equal((await bundles(built)).some(value => value.versionId === createdVersion), false);
  assert.equal(await panel.locator('input[name=name]').inputValue(), ' Edited in Atlas '); assert.equal(await panel.locator('input[name=date]').inputValue(), '2027-02-01');
  await panel.getByRole('button', { name: 'Save details', exact: true }).click(); await panel.getByText('Release details saved.', { exact: true }).waitFor();
  await page.getByRole('heading', { name: 'Edited in Atlas', exact: true }).waitFor({ state: 'attached' });
  let stored = (await read(program)).find(value => value.id === built); assert.equal(stored.name, 'Edited in Atlas'); assert.equal(stored.description, 'Unsent release edit'); assert.equal(new Date(stored.releaseDate).toISOString(), '2027-02-01T00:00:00.000Z');
  await gql('mutation($id:UUID!,$version:Long!){workOps{crossProject{updateRelease(id:$id,expectedVersion:$version,input:{name:"Externally updated release",description:"Concurrent saved notes"}){id}}}}', { id: built, version: stored.version });
  await panel.locator('input[name=name]').fill('Retained stale detail'); await panel.locator('textarea[name=description]').fill('Retained stale notes'); await panel.locator('input[name=date]').fill('2027-03-15');
  assert.equal(await panel.getByRole('button', { name: 'Retry loading project versions', exact: true }).count(), 0);
  expected(true); await panel.getByRole('button', { name: 'Save details', exact: true }).click(); await panel.locator('[data-workops-release-edit-error]').waitFor(); expected(false);
  assert.equal(await panel.locator('input[name=name]').inputValue(), 'Retained stale detail'); assert.equal(await panel.locator('textarea[name=description]').inputValue(), 'Retained stale notes'); assert.equal(await panel.locator('input[name=date]').inputValue(), '2027-03-15');
  assert.equal((await read(program)).find(value => value.id === built).name, 'Externally updated release');
  await panel.getByRole('link', { name: 'Done', exact: true }).click(); await page.getByRole('heading', { name: 'Externally updated release', exact: true }).waitFor();
  assert.equal(await page.locator('[data-workops-release-component]').count(), 3); return added;
}

export async function releaseReaderEdits({ reader, base, release, gql, read, bundles, program, permission, dialog, expected }) {
  await reader.goto(base + `/workops/releases/${release}`); await reader.getByRole('button', { name: 'Edit release', exact: true }).click(); const panel = dialog('Edit release', reader); await panel.waitFor();
  await panel.locator('input[name=name]').fill('Reader edited release'); await panel.locator('textarea[name=description]').fill('Retained permission edit'); await panel.locator('input[name=date]').fill('2027-04-01');
  await permission(program, 'MANAGE', false); expected(true); await panel.getByRole('button', { name: 'Save details', exact: true }).click(); await panel.locator('[data-workops-release-edit-error]').waitFor(); expected(false);
  assert.equal(await panel.locator('input[name=name]').inputValue(), 'Reader edited release'); assert.equal(await panel.locator('input[name=date]').inputValue(), '2027-04-01'); assert.equal((await read(program)).find(value => value.id === release).name, 'Reader release');
  await permission(program, 'MANAGE', true); await panel.getByRole('button', { name: 'Save details', exact: true }).click(); await panel.getByText('Release details saved.', { exact: true }).waitFor();
  assert.equal((await read(program)).find(value => value.id === release).name, 'Reader edited release'); await panel.getByRole('link', { name: 'Done', exact: true }).click();
  await reader.getByRole('heading', { name: 'Reader edited release', exact: true }).waitFor(); await reader.getByRole('button', { name: 'Delete release', exact: true }).click(); const removal = dialog('Delete release', reader); await removal.waitFor();
  await permission(program, 'MANAGE', false); await removal.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); expected(true);
  await removal.getByRole('button', { name: 'Delete release', exact: true }).click(); await removal.getByRole('alert').waitFor(); expected(false);
  assert.equal((await read(program)).some(value => value.id === release), true); assert.equal((await bundles(release)).length, 1);
  await permission(program, 'MANAGE', true); await removal.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await removal.getByRole('button', { name: 'Delete release', exact: true }).click();
  await reader.waitForURL(url => url.pathname === '/workops/releases' && url.searchParams.get('programId') === program); assert.equal((await read(program)).some(value => value.id === release), false);
  assert.equal((await gql('query($id:UUID!){workOps{crossProject{release(id:$id){id}}}}', { id: release })).workOps.crossProject.release, null);
}

export async function releaseDetailLayouts({ page, context, base, built, reports, dialog, choose }) {
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 1000 }); await page.goto(base + `/workops/releases/${built}`);
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await releaseHeaderControls(page, width);
    const pipelines = page.locator('[data-workops-release-ci]');
    assert.equal(await pipelines.locator('[data-workops-release-ci-plans-error]').count(), 0, 'Unattached repositories are setup guidance, not a failed plan read');
    await pipelines.getByText('No release plans', { exact: true }).waitFor();
    assert.equal(await pipelines.getByRole('button', { name: 'Start release', exact: true }).getAttribute('title'), 'Attach a Git repository to a bundled project before starting the release.');
    assert.equal(await page.getByRole('heading', { name: 'Release overview', exact: true }).count(), 0);
    assert.equal(await page.locator('[data-workops-release-summary]').evaluate(node => node.classList.contains('ui-card')), true);
    const workspace = page.locator('[data-workops-release-workspace]');
    assert.equal(await workspace.evaluate(node => getComputedStyle(node).gridTemplateColumns.split(' ').length), width > 1200 ? 2 : 1);
    for (const marker of ['ci', 'environments', 'artifacts', 'notes', 'health', 'store-health', 'live']) {
      const section = page.locator(`[data-workops-release-${marker}]`); await section.waitFor();
      assert.equal(await section.evaluate(node => !!node.closest('.ui-card') && !node.classList.contains('ui-section')), true, `${marker} uses a shared card and native domain container`);
    }
    for (const control of await page.locator('[data-workops-release-content] .section-heading .heading-actions').locator('button, a').all()) {
      if (await control.isVisible()) assert.equal((await control.boundingBox()).height, 32, 'Section toolbar controls share the medium size');
    }
    assert.equal(await page.locator('[data-workops-release-content] > p:empty').count(), 0, 'Alerts do not create empty layout rows');
    await page.screenshot({ path: resolve(reports, `overview-${appearance}-${width}.png`) });

    await page.getByRole('button', { name: 'Edit release', exact: true }).click(); const panel = dialog('Edit release'); await panel.waitFor();
    await panel.locator('input[name=name]').fill('Unsent layout edit'); await panel.locator('textarea[name=description]').fill('Retained dialog fields'); await panel.locator('input[name=date]').fill('2027-02-01');
    await choose(panel, 'Project', 'RAPP1 · Application one'); await panel.getByText('Versions for: RAPP1 · Application one', { exact: true }).waitFor();
    await panel.getByRole('link', { name: 'Done', exact: true }).scrollIntoViewIfNeeded();
    const box = await panel.boundingBox(); assert.ok(box && box.x >= 0 && box.x + box.width <= width + 1 && box.y >= 0 && box.y + box.height <= 1001, JSON.stringify(box));
    await page.screenshot({ path: resolve(reports, `edit-${appearance}-${width}.png`), fullPage: true });
    await panel.getByRole('button', { name: 'New version', exact: true }).click(); const version = dialog('New project version'); await version.waitFor();
    await version.locator('input[name=name]').fill('Unsent edit version'); await version.locator('input[name=startDate]').fill('2027-01-01'); await version.getByRole('button', { name: 'Create version', exact: true }).scrollIntoViewIfNeeded();
    await page.screenshot({ path: resolve(reports, `edit-version-${appearance}-${width}.png`), fullPage: true }); await version.getByRole('button', { name: 'Cancel', exact: true }).click(); await version.waitFor({ state: 'hidden' });
    assert.equal(await panel.locator('input[name=name]').inputValue(), 'Unsent layout edit');
    await panel.locator('[data-workops-release-edit-version]').first().getByRole('button', { name: /^Remove / }).click(); const removal = dialog('Remove bundled version'); await removal.waitFor();
    await page.screenshot({ path: resolve(reports, `edit-remove-${appearance}-${width}.png`), fullPage: true }); await removal.getByRole('button', { name: 'Cancel', exact: true }).click(); await removal.waitFor({ state: 'hidden' });
    await page.locator('[data-workops-release-edit] [data-bml-action-pending]').waitFor({ state: 'detached' });
    await page.waitForFunction(() => [...document.querySelectorAll('dialog[open]')].some(node => node.contains(document.activeElement) && node.textContent.includes('Edit release')));
    assert.equal(await panel.locator('input[name=date]').inputValue(), '2027-02-01'); assert.ok(await panel.evaluate(node => node.contains(document.activeElement)), 'Focus returns to the release editor.'); await panel.getByRole('link', { name: 'Done', exact: true }).click();
    await page.getByRole('button', { name: 'Delete release', exact: true }).click(); const deletion = dialog('Delete release'); await deletion.waitFor();
    await page.screenshot({ path: resolve(reports, `edit-delete-${appearance}-${width}.png`), fullPage: true }); await deletion.getByRole('button', { name: 'Cancel', exact: true }).click(); await deletion.waitFor({ state: 'hidden' });
  }
}
