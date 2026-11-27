import { compactResult } from './presentation-checks.mjs';
import assert from 'node:assert/strict';

/** Global types retain their administrator boundary independently of program management. */
export async function environmentTypes({ page, base, gql, dialog, reject }) {
  const path = '/workops/settings/environment-types';
  const row = id => page.locator(`[data-workops-environment-type="${id}"]`);
  const read = async () => (await gql('{workOps{multiRepo{environmentTypes{id name description displayOrder version}}}}')).workOps.multiRepo.environmentTypes;
  const open = async (name, description, order) => {
    await page.getByRole('button', { name: 'New environment type', exact: true }).click(); const panel = dialog('New environment type'); await panel.waitFor();
    await panel.locator('input[name=name]').fill(name); await panel.locator('textarea[name=description]').fill(description); await panel.locator('input[name=order]').fill(String(order)); return panel;
  };
  const submit = async panel => {
    await panel.getByRole('button', { name: 'Save environment type', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
    const result = page.getByRole('link', { name: 'Show the saved environment type', exact: true }); await result.waitFor(); await compactResult(result);
    assert.equal(await result.locator('xpath=ancestor::header[contains(@class,"page-heading")]').count(), 1, 'Saved-item navigation stays in the page actions');
    assert.equal((await result.innerText()).trim(), 'Saved type');
    assert.ok(Number.parseFloat(await result.evaluate(node => getComputedStyle(node).fontSize)) <= 12);
    return new URL(await result.getAttribute('href'), base).searchParams.get('item');
  };
  await page.goto(base + path);
  let panel = await open(' Atlas QA stage ', ' Initial stage notes ', 3); const id = await submit(panel);
  let stored = (await read()).find(value => value.id === id);
  assert.equal(stored.name, 'Atlas QA stage'); assert.equal(stored.description, 'Initial stage notes'); assert.equal(stored.displayOrder, 3);
  await row(id).getByRole('button', { name: 'Edit Atlas QA stage', exact: true }).click(); panel = dialog('Edit environment type'); await panel.waitFor();
  await panel.locator('input[name=name]').fill('Retained QA draft'); await panel.locator('textarea[name=description]').fill('Retained type notes'); await panel.locator('input[name=order]').fill('4');
  await gql('mutation($id:UUID!,$version:Long!){workOps{multiRepo{updateEnvironmentType(id:$id,name:"Externally updated stage",description:"External notes",displayOrder:5,expectedVersion:$version){id}}}}', { id, version: stored.version });
  await reject(panel, 'Save environment type');
  await panel.getByRole('button', { name: 'Retry loading environment types', exact: true }).click(); await panel.page().waitForFunction(() => !document.querySelector('[data-bml-action-pending]'));
  await page.waitForFunction(() => document.querySelector('dialog[open] input[name=name]')?.getAttribute('value') === 'Retained QA draft');
  await reject(panel, 'Save environment type');
  assert.equal(await panel.locator('input[name=name]').inputValue(), 'Retained QA draft'); assert.equal(await panel.locator('input[name=order]').inputValue(), '4');
  assert.equal((await read()).find(value => value.id === id).name, 'Externally updated stage');
  await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
  await page.waitForFunction(() => !document.querySelector('[data-bml-action-pending]')); await page.getByRole('button', { name: 'Refresh environment types', exact: true }).click(); await page.waitForFunction(() => !document.querySelector('[data-bml-action-pending]')); await row(id).getByRole('button', { name: 'Edit Externally updated stage', exact: true }).click(); await panel.waitFor();
  await panel.locator('input[name=name]').fill('Atlas QA stage'); await panel.locator('textarea[name=description]').fill('Saved stage notes'); await panel.locator('input[name=order]').fill('2'); await submit(panel);
  stored = (await read()).find(value => value.id === id); assert.equal(stored.name, 'Atlas QA stage'); assert.equal(stored.displayOrder, 2); assert.equal(stored.description, 'Saved stage notes');
  panel = await open('Disposable environment type', '', 9); const disposable = await submit(panel);
  await row(disposable).getByRole('button', { name: 'Delete Disposable environment type', exact: true }).click(); const deleting = dialog('Delete environment type'); await deleting.waitFor();
  await deleting.getByRole('button', { name: 'Cancel', exact: true }).click(); await deleting.waitFor({ state: 'hidden' }); assert.ok((await read()).some(value => value.id === disposable));
  await row(disposable).getByRole('button', { name: 'Delete Disposable environment type', exact: true }).click(); await deleting.waitFor(); await deleting.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check();
  await deleting.getByRole('button', { name: 'Delete environment type', exact: true }).click(); await deleting.waitFor({ state: 'hidden' }); assert.equal((await read()).some(value => value.id === disposable), false);
  return { id, name: 'Atlas QA stage', path, row, read, open, submit };
}
