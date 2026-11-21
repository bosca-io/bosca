import assert from 'node:assert/strict';
import { resolve } from 'node:path';

/** Column and settings interactions use the imported UI; API reads verify the persisted result. */
export async function configureBoard({ page, context, base, board, project, statuses, gql, choose, dialog, reports, rejection, observe }) {
  const path = `/workops/boards/${board}`, columns = {};
  const row = (id, tab = page) => tab.locator(`[data-workops-column="${id}"]`);
  const read = async () => (await gql('query($id:UUID!){workOps{boards{board(id:$id){id name type swimlaneStrategy version projects{id} columns{id name displayOrder statusIds wipLimit}}}}}', { id: board })).workOps.boards.board;
  await page.getByRole('link', { name: 'Configure columns', exact: true }).click();
  await page.waitForURL(url => url.pathname === path && url.searchParams.get('tab') === 'columns');
  for (const [name, category] of [['To do', 'TODO'], ['Working', 'IN_PROGRESS'], ['Done', 'DONE']]) {
    await page.getByRole('button', { name: 'Add column', exact: true }).click(); const editor = dialog('Add column'); await editor.waitFor();
    await editor.locator('input[name=name]').fill(name);
    await editor.getByRole('combobox', { name: 'Statuses', exact: true }).click();
    for (const status of statuses.filter(value => value.category === category)) await page.getByRole('option', { name: status.name, exact: true }).click();
    await page.keyboard.press('Escape');
    if (name === 'To do') await editor.locator('input[name=limit]').fill('1');
    await editor.getByRole('button', { name: 'Save column', exact: true }).click(); await editor.waitFor({ state: 'hidden' });
    const persisted = (await read()).columns.find(column => column.name === name); assert.ok(persisted);
    assert.deepEqual(new Set(persisted.statusIds), new Set(statuses.filter(value => value.category === category).map(value => value.id)));
    assert.equal(persisted.wipLimit, name === 'To do' ? 1 : null); columns[name] = persisted.id;
    await row(persisted.id).waitFor();
  }
  assert.deepEqual((await read()).columns.map(value => value.displayOrder).sort((a, b) => a - b), [1, 2, 3]);

  // The new fourth column occupies the temporary order the stale UI would use for a swap.
  const cancelled = statuses.filter(value => value.category === 'CANCELLED').map(value => value.id); assert.ok(cancelled.length);
  const concurrent = (await gql('mutation($input:CreateWorkOpsBoardColumnInput!){workOps{boards{addColumn(input:$input){id}}}}', {
    input: { boardId: board, name: 'Concurrent column', displayOrder: 4, statusIds: cancelled },
  })).workOps.boards.addColumn.id;
  rejection(true); await row(columns.Working).getByRole('button', { name: 'Move Working earlier', exact: true }).click();
  await page.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); rejection(false);
  await row(concurrent).waitFor(); assert.equal((await read()).columns.find(value => value.id === columns['To do']).displayOrder, 1);
  await row(concurrent).getByRole('button', { name: 'Delete Concurrent column', exact: true }).click(); const deleting = dialog('Delete column');
  await deleting.waitFor(); await deleting.getByRole('button', { name: 'Cancel', exact: true }).click(); await deleting.waitFor({ state: 'hidden' });
  assert.ok((await read()).columns.some(value => value.id === concurrent));
  await row(concurrent).getByRole('button', { name: 'Delete Concurrent column', exact: true }).click();
  await deleting.getByRole('checkbox', { name: 'Delete this column', exact: true }).check();
  await deleting.getByRole('button', { name: 'Delete column', exact: true }).click(); await deleting.waitFor({ state: 'hidden' });
  assert.equal((await read()).columns.some(value => value.id === concurrent), false);
  await row(columns.Working).getByRole('button', { name: 'Move Working earlier', exact: true }).click();
  await row(columns.Working).locator('td').first().getByText('1', { exact: true }).waitFor();
  assert.equal((await read()).columns.find(value => value.id === columns.Working).displayOrder, 1);
  await row(columns.Working).getByRole('button', { name: 'Move Working later', exact: true }).click();
  await row(columns.Working).locator('td').first().getByText('2', { exact: true }).waitFor();
  assert.equal((await read()).columns.find(value => value.id === columns.Working).displayOrder, 2);

  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]); await page.setViewportSize({ width, height: 1000 });
    await page.goto(`${base}${path}?tab=columns`);
    await row(columns['To do']).getByRole('button', { name: 'Edit To do', exact: true }).click(); const editor = dialog('Edit column'); await editor.waitFor();
    assert.equal(await editor.locator('input[name=limit]').inputValue(), '1');
    await editor.getByRole('combobox', { name: 'Statuses', exact: true }).click();
    const offered = await page.getByRole('option').allTextContents();
    assert.equal(offered.some(label => statuses.some(status => status.category === 'IN_PROGRESS' && label.trim() === status.name)), false);
    await page.keyboard.press('Escape');
    await editor.locator('input[name=limit]').fill('0');
    const geometry = await editor.evaluate(node => { const rect = node.getBoundingClientRect(); return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom, width: innerWidth, height: innerHeight }; });
    assert.ok(geometry.left >= -1 && geometry.right <= geometry.width + 1 && geometry.top >= -1 && geometry.bottom <= geometry.height + 1, JSON.stringify(geometry));
    await page.screenshot({ path: resolve(reports, `column-${appearance}-${width}.png`), fullPage: true });
    await editor.getByRole('button', { name: 'Save column', exact: true }).click(); await editor.waitFor({ state: 'hidden' });
    assert.equal((await read()).columns.find(value => value.id === columns['To do']).wipLimit, null);
    await row(columns['To do']).getByRole('button', { name: 'Edit To do', exact: true }).click();
    await editor.locator('input[name=limit]').fill('1'); await editor.getByRole('button', { name: 'Save column', exact: true }).click(); await editor.waitFor({ state: 'hidden' });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(`${base}${path}?tab=settings`); const settings = page.locator('[data-board-tab="settings"] form').first();
  assert.equal(await settings.getByRole('combobox', { name: 'Projects', exact: true }).count(), 0, 'Single-parent boards must not expose a multi-project editor');
  await settings.locator('input[name=name]').fill('Retained board settings draft');
  const original = await read();
  await gql('mutation($id:UUID!,$input:UpdateWorkOpsBoardInput!){workOps{boards{update(id:$id,input:$input){id}}}}', {
    id: board, input: { name: 'Newer board settings', type: original.type, swimlaneStrategy: original.swimlaneStrategy, expectedVersion: original.version },
  });
  rejection(true); await settings.getByRole('button', { name: 'Save board settings', exact: true }).click();
  await page.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); rejection(false);
  assert.equal(await settings.locator('input[name=name]').inputValue(), 'Retained board settings draft');
  await page.getByRole('button', { name: 'Reload board configuration', exact: true }).click();
  await page.getByRole('heading', { name: 'Newer board settings', exact: true }).waitFor();
  assert.equal(await settings.locator('input[name=name]').inputValue(), 'Retained board settings draft');
  await settings.getByRole('button', { name: 'Reset draft to loaded board', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('[data-board-tab="settings"] input[name="name"]')?.value === 'Newer board settings');
  assert.equal(await settings.locator('input[name=name]').inputValue(), 'Newer board settings');
  await settings.locator('input[name=name]').fill('Atlas delivery board');
  await settings.getByRole('button', { name: 'Save board settings', exact: true }).click();
  await page.getByText('Board settings saved.', { exact: true }).waitFor(); assert.equal((await read()).name, 'Atlas delivery board');

  const permission = async (action, add) => gql(`mutation($id:UUID!,$group:UUID!){workOps{projects{${add ? 'add' : 'remove'}Permission(id:$id,groupId:$group,action:${action})}}}`, { id: project, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
  await permission('VIEW', true); await permission('MANAGE', true);
  const readerContext = await context.browser().newContext(), reader = await readerContext.newPage();
  observe(reader);
  try {
    await reader.goto(`${base}${path}?tab=settings`); await reader.locator('input[name=identifier]').fill(process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER);
    await reader.locator('input[name=password]').fill(process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD); await reader.locator('button[type=submit]').click();
    await reader.waitForURL(url => url.pathname === path);
    const form = reader.locator('[data-board-tab="settings"] form').first(); await form.locator('input[name=name]').fill('Denied settings draft');
    await permission('MANAGE', false); rejection(true);
    await form.getByRole('button', { name: 'Save board settings', exact: true }).click(); await reader.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); rejection(false);
    assert.equal(await form.locator('input[name=name]').inputValue(), 'Denied settings draft');
    assert.equal((await read()).name, 'Atlas delivery board');
    await permission('MANAGE', true); await form.getByRole('button', { name: 'Save board settings', exact: true }).click();
    await reader.getByText('Board settings saved.', { exact: true }).waitFor(); assert.equal((await read()).name, 'Denied settings draft');
  } finally { await readerContext.close(); await permission('MANAGE', false); await permission('VIEW', false); }
  await page.getByRole('navigation', { name: 'Board views', exact: true }).getByRole('link', { name: 'Board', exact: true }).click();
  await page.locator('[data-workops-board-instance="detail"] [data-bml-board]').waitFor();
  await page.goto(`${base}/workops/boards?projectId=${project}&boardId=${board}`);
  assert.equal(await page.getByRole('link', { name: 'Configure', exact: true }).getAttribute('href'), `${path}?tab=settings`);
  return columns;
}
