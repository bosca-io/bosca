import { compactResult } from './presentation-checks.mjs';
import assert from 'node:assert/strict';

/** Exercise each owning catalog API through its compiled BML controls and verify stored results. */
export async function projectCatalogs({ page, base, project, href, gql, choose, dialog, rejection }) {
  const read = async () => (await gql('query($id:UUID!){workOps{versions{byProject(projectId:$id){id name description released releaseDate version}} components{byProject(projectId:$id){id name description assigneeMode}} labels{byProject(projectId:$id){id name colorHex}}}}', { id: project })).workOps;
  const scope = kind => page.locator(`[data-project-catalog-kind="${kind}"]`);
  const create = async (kind, name, description = '') => {
    const entity = kind.slice(0, -1); await scope(kind).getByRole('button', { name: `New ${entity}`, exact: true }).click();
    const panel = dialog(`New ${entity}`); await panel.waitFor(); await panel.locator('input[name=name]').fill(name);
    if (kind !== 'labels') await panel.locator('textarea[name=description]').fill(description);
    return panel;
  };
  const submit = async (kind, panel) => {
    await panel.getByRole('button', { name: `Create ${kind.slice(0, -1)}`, exact: true }).click(); await panel.waitFor({ state: 'hidden' });
    const result = scope(kind).getByRole('link', { name: `Show the created ${kind.slice(0, -1)}`, exact: true }); await result.waitFor(); await compactResult(result);
    return new URL(await result.getAttribute('href'), base).searchParams.get('item');
  };
  await page.goto(`${base}${href}?tab=versions`);
  let panel = await create('versions', '1.2.3', 'First detailed release'); let id = await submit('versions', panel);
  let stored = (await read()).versions.byProject.find(row => row.id === id); assert.equal(stored.name, '1.2.3'); assert.equal(stored.description, 'First detailed release');
  await scope('versions').locator(`[data-workops-project-catalog-row="${id}"]`).getByRole('button', { name: 'Release 1.2.3', exact: true }).click();
  const releasing = dialog('Release version'); await releasing.waitFor();
  await gql('mutation($id:UUID!,$version:Long!){workOps{versions{release(id:$id,expectedVersion:$version){id}}}}', { id, version: stored.version });
  await releasing.getByRole('checkbox', { name: 'Confirm release', exact: true }).check(); rejection(true);
  await releasing.getByRole('button', { name: 'Release version', exact: true }).click(); await releasing.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); rejection(false);
  assert.equal((await read()).versions.byProject.find(row => row.id === id).released, true);
  await releasing.getByRole('button', { name: 'Cancel', exact: true }).click(); await releasing.waitFor({ state: 'hidden' });
  await scope('versions').getByRole('button', { name: 'Refresh versions', exact: true }).click();
  await scope('versions').locator(`[data-workops-project-catalog-row="${id}"]`).getByText('Released', { exact: true }).waitFor();
  panel = await create('versions', '2.0.0', 'Released through Atlas'); id = await submit('versions', panel);
  await scope('versions').locator(`[data-workops-project-catalog-row="${id}"]`).getByRole('button', { name: 'Release 2.0.0', exact: true }).click(); await releasing.waitFor();
  await releasing.getByRole('checkbox', { name: 'Confirm release', exact: true }).check();
  await releasing.getByRole('button', { name: 'Release version', exact: true }).click(); await releasing.waitFor({ state: 'hidden' });
  await scope('versions').locator(`[data-workops-project-catalog-row="${id}"]`).getByText('Released', { exact: true }).waitFor();
  stored = (await read()).versions.byProject.find(row => row.id === id); assert.equal(stored.released, true); assert.ok(stored.releaseDate);

  await page.goto(`${base}${href}?tab=components`); panel = await create('components', 'Detail component', 'Component description');
  await choose(panel, 'Assignee mode', 'Project default'); id = await submit('components', panel);
  stored = (await read()).components.byProject.find(row => row.id === id); assert.equal(stored.assigneeMode, 'PROJECT_DEFAULT'); assert.equal(stored.description, 'Component description');
  const deletion = dialog('Delete component');
  await scope('components').locator(`[data-workops-project-catalog-row="${id}"]`).getByRole('button', { name: 'Delete Detail component', exact: true }).click(); await deletion.waitFor();
  await deletion.getByRole('button', { name: 'Cancel', exact: true }).click(); await deletion.waitFor({ state: 'hidden' }); assert.ok((await read()).components.byProject.some(row => row.id === id));
  await scope('components').locator(`[data-workops-project-catalog-row="${id}"]`).getByRole('button', { name: 'Delete Detail component', exact: true }).click(); await deletion.waitFor();
  await deletion.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check(); await deletion.getByRole('button', { name: 'Delete component', exact: true }).click(); await deletion.waitFor({ state: 'hidden' });
  await scope('components').locator(`[data-workops-project-catalog-row="${id}"]`).waitFor({ state: 'detached' }); assert.equal((await read()).components.byProject.some(row => row.id === id), false);
  panel = await create('components', 'Retained component', 'A configured component'); await choose(panel, 'Assignee mode', 'Component lead or project default'); await submit('components', panel);

  await page.goto(`${base}${href}?tab=labels`); panel = await create('labels', 'Bad label');
  await panel.locator('input[name=color]').evaluate(node => { node.value = '#123456'; node.dispatchEvent(new Event('input', { bubbles: true })); node.dispatchEvent(new Event('change', { bubbles: true })); });
  rejection(true); await panel.getByRole('button', { name: 'Create label', exact: true }).click(); await panel.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); rejection(false);
  assert.equal(await panel.locator('input[name=name]').inputValue(), 'Bad label'); assert.equal(await panel.locator('input[name=color]').inputValue(), '#123456');
  await panel.locator('input[name=name]').fill('detail-label'); id = await submit('labels', panel);
  assert.equal((await read()).labels.byProject.find(row => row.id === id).colorHex, '#123456');
  panel = await create('labels', 'delete-label'); const removed = await submit('labels', panel);
  await scope('labels').locator(`[data-workops-project-catalog-row="${removed}"]`).getByRole('button', { name: 'Delete delete-label', exact: true }).click();
  const deletingLabel = dialog('Delete label'); await deletingLabel.waitFor(); await deletingLabel.getByRole('checkbox', { name: 'Confirm deletion', exact: true }).check();
  await deletingLabel.getByRole('button', { name: 'Delete label', exact: true }).click(); await deletingLabel.waitFor({ state: 'hidden' });
  await scope('labels').locator(`[data-workops-project-catalog-row="${removed}"]`).waitFor({ state: 'detached' }); assert.equal((await read()).labels.byProject.some(row => row.id === removed), false);

  for (let index = 0; index < 26; index++) await gql('mutation($input:CreateWorkOpsVersionInput!){workOps{versions{create(input:$input){id}}}}', { input: { projectId: project, name: `Extra version ${index}` } });
  await page.goto(`${base}${href}?tab=versions`); assert.equal(await scope('versions').locator('[data-workops-project-catalog-row]').count(), 25);
  await page.getByRole('navigation', { name: 'Versions pages', exact: true }).getByRole('link', { name: 'Next', exact: true }).click(); await page.waitForURL(url => url.searchParams.get('offset') === '25');
  assert.equal(await scope('versions').locator('[data-workops-project-catalog-row]').count(), 3);
  panel = await create('versions', 'Version result link', 'Created on a later page'); id = await submit('versions', panel);
  await scope('versions').getByRole('link', { name: 'Show the created version', exact: true }).click();
  await scope('versions').locator(`[data-workops-project-catalog-row="${id}"]`).waitFor();
  assert.equal(new URL(page.url()).searchParams.get('item'), id);
}
