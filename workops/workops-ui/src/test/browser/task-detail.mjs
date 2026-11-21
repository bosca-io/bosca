import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';
import { referenceDetails } from './reference-details.mjs';
import { documentTemplate } from './document-template.mjs';
import { documentReferences } from './document-references.mjs';
import { documentImages } from './document-images.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL;
const peer = process.env.ATLAS_WORKOPS_TEST_PEER_URL;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname), 'Use the isolated local Atlas fixture.');
assert.ok(peer && peer !== base && ['localhost', '127.0.0.1'].includes(new URL(peer).hostname), 'Use a second isolated Atlas UI process.');
const key = process.env.ATLAS_WORKOPS_TEST_TASK_KEY;
const mentionHandle = process.env.ATLAS_WORKOPS_TEST_MENTION_HANDLE;
assert.match(mentionHandle, /^[A-Za-z0-9._-]+$/);
assert.ok(key && process.env.ATLAS_WORKOPS_TEST_PASSWORD);
const reports = resolve('build/reports/task-detail-browser', new URL(base).port);
await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0);
await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), page = await context.newPage();
const errors = [];
const mentionResponses = [];
const observe = tab => {
  tab.on('pageerror', error => errors.push(error.message));
  tab.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  tab.on('response', async response => {
    if (new URL(response.url()).pathname.startsWith('/_bml/contract/WorkOpsMentionSearch/')) {
      mentionResponses.push({ path: new URL(response.url()).pathname, query: response.request().postData(),
        response: await response.text().catch(() => 'Response unavailable') });
    }
  });
};
observe(page);
const dialog = (name, tab = page) => tab.getByRole('dialog', { name, exact: true });
const feedback = async text => page.getByText(text, { exact: true }).waitFor({ timeout: 15000 });
const edit = async tab => {
  await tab.getByRole('button', { name: 'Edit task', exact: true }).click();
  await dialog('Edit task', tab).waitFor();
  return dialog('Edit task', tab);
};
const startDate = () => page.locator('dt', { hasText: /^Start date$/ }).locator('+ dd time');
const checkStartDate = async () => {
  assert.equal(await startDate().textContent(), 'Nov 1, 2026');
  assert.equal(Date.parse(await startDate().getAttribute('datetime')), Date.parse('2026-11-01T23:45:00Z'));
};
try {
  await page.goto(`${base}/workops/tasks/${key}`);
  await page.waitForURL(url => url.pathname === '/login');
  assert.ok(new URL(page.url()).searchParams.get('redirect')?.includes(key));
  await page.locator('input[name=identifier]').fill('admin');
  await page.locator('input[name=password]').fill(process.env.ATLAS_WORKOPS_TEST_PASSWORD);
  await page.locator('button[type=submit]').click();
  await page.waitForURL(url => url.pathname.includes(`/tasks/${key}`));
  await page.getByRole('heading', { name: 'Updated by permitted reader', exact: true }).waitFor();
  await checkStartDate();
  await page.getByRole('button', { name: /^Unwatch/ }).click();
  await feedback('Stopped watching this task.');
  await page.getByRole('button', { name: /^Watch \(/ }).click();
  await feedback('Now watching this task.');

  const stalePage = await context.newPage();
  observe(stalePage);
  await stalePage.goto(`${base}/workops/tasks/${key}`);
  const stale = await edit(stalePage);
  await stale.locator('input[name=summary]').fill('Retained stale browser draft');
  const editing = await edit(page);
  await editing.locator('input[name=summary]').fill('Task detail browser acceptance');
  const markdown = editing.locator('[data-bml-code-editor] .cm-content');
  await markdown.waitFor();
  await markdown.fill('Description edited through the shared CodeMirror component.');
  await editing.locator('input[name=dueDate]').fill('2026-12-03');
  await editing.locator('input[name=originalEstimate]').fill('1h 30m');
  await editing.locator('input[name=remainingEstimate]').fill('45m');
  await editing.getByRole('button', { name: 'Save task', exact: true }).click();
  await editing.waitFor({ state: 'hidden' });
  await feedback('Task saved.');
  await page.getByRole('heading', { name: 'Task detail browser acceptance', exact: true }).waitFor();
  await checkStartDate();
  const retainedStartDate = await page.evaluate(async key => {
    const response = await fetch('/graphql', {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` },
      body: JSON.stringify({ query: 'query($key:String!){workOps{tasks{taskByKey(key:$key){startDate}}}}', variables: { key } }),
    });
    return response.json();
  }, key);
  assert.equal(retainedStartDate.errors, undefined, JSON.stringify(retainedStartDate));
  assert.equal(Date.parse(retainedStartDate.data.workOps.tasks.taskByKey.startDate), Date.parse('2026-11-01T23:45:00Z'));
  await stale.getByRole('button', { name: 'Save task', exact: true }).click();
  await stale.getByText('Internal server error', { exact: false }).waitFor();
  assert.equal(await stale.locator('input[name=summary]').inputValue(), 'Retained stale browser draft');
  assert.equal(await stale.isVisible(), true);
  await stale.getByRole('button', { name: 'Cancel', exact: true }).click();
  await stalePage.close();

  await page.getByRole('button', { name: 'Start work', exact: true }).click();
  await feedback('Task status updated.');
  await page.getByRole('button', { name: 'Resolve', exact: true }).click();
  const resolving = dialog('Resolve task');
  await resolving.waitFor();
  const resolution = await resolving.locator('select[name=resolutionId]').evaluate(select => [...select.options].find(option => option.value)?.textContent);
  assert.ok(resolution);
  await resolving.getByRole('combobox', { name: 'Resolution', exact: true }).click();
  await page.getByRole('option', { name: resolution, exact: true }).click();
  await resolving.getByRole('button', { name: 'Resolve task', exact: true }).click();
  await resolving.waitFor({ state: 'hidden' });
  await feedback('Task status updated.');

  const comment = page.locator('textarea[name=content]');
  await comment.fill(`@${mentionHandle.slice(0, -1)}`);
  const commentMentions = page.getByRole('listbox', { name: 'Profile mentions', exact: true });
  await commentMentions.getByRole('option', { name: /Mention Candidate/ }).waitFor();
  await comment.press('Enter');
  assert.equal(await comment.inputValue(), `@${mentionHandle} `);
  assert.equal(await comment.evaluate(field => field === document.activeElement), true);
  await comment.press('Control+Enter');
  await feedback('Comment added.');
  await page.getByText(`@${mentionHandle}`, { exact: true }).waitFor();
  await comment.fill('Task detail acceptance comment.');
  await comment.press('Control+Enter');
  await feedback('Comment added.');
  await page.getByText('Task detail acceptance comment.', { exact: true }).waitFor();
  assert.equal(await page.locator('textarea[name=content]').inputValue(), '');

  await page.getByRole('button', { name: 'Log work', exact: true }).click();
  const logging = dialog('Log work');
  await logging.waitFor();
  await logging.locator('input[name=time]').fill('30m');
  await logging.locator('textarea[name=comment]').fill('Browser work log');
  await logging.getByRole('button', { name: 'Log work', exact: true }).click();
  await logging.waitFor({ state: 'hidden' });
  await feedback('Work logged.');
  await page.getByRole('link', { name: 'Work log', exact: true }).click();
  await page.getByText('Browser work log', { exact: true }).waitFor();
  const deleteWorkLog = page.getByRole('button', { name: 'Delete work log', exact: true });
  assert.equal((await deleteWorkLog.innerText()).trim(), ''); assert.equal(await deleteWorkLog.locator('svg').count(), 1);
  await deleteWorkLog.click();
  const deleting = dialog('Delete work log');
  await deleting.getByRole('checkbox', { name: 'Delete this work log', exact: true }).check();
  await deleting.getByRole('button', { name: 'Delete work log', exact: true }).click();
  await deleting.waitFor({ state: 'hidden' });
  await feedback('Work log deleted.');
  assert.equal(await page.getByText('Browser work log', { exact: true }).count(), 0);

  await page.getByRole('button', { name: 'Log work', exact: true }).click();
  await logging.waitFor();
  await logging.locator('input[name=time]').fill('15m');
  await logging.locator('textarea[name=comment]').fill('Concurrent browser work log');
  await logging.getByRole('button', { name: 'Log work', exact: true }).click();
  await logging.waitFor({ state: 'hidden' });
  await feedback('Work logged.');
  await page.getByText('Concurrent browser work log', { exact: true }).waitFor();
  const concurrentlyRemoved = await page.evaluate(async key => {
    const execute = async (query, variables) => {
      const response = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) });
      const result = await response.json();
      if (result.errors) throw new Error(JSON.stringify(result.errors));
      return result.data.workOps;
    };
    const task = await execute('query($key:String!){workOps{tasks{taskByKey(key:$key){id}}}}', { key });
    const logs = await execute('query($id:UUID!){workOps{worklogs{forTask(taskId:$id,offset:0,limit:100){id comment}}}}', { id: task.tasks.taskByKey.id });
    const entry = logs.worklogs.forTask.find(row => row.comment === 'Concurrent browser work log');
    if (!entry) throw new Error('The isolated work log was not persisted.');
    return execute('mutation($id:UUID!){workOps{worklogs{deleteWorklog(id:$id)}}}', { id: entry.id });
  }, key);
  assert.equal(concurrentlyRemoved.worklogs.deleteWorklog, true);
  await page.getByRole('button', { name: 'Delete work log', exact: true }).click();
  await deleting.getByRole('checkbox', { name: 'Delete this work log', exact: true }).check();
  await deleting.getByRole('button', { name: 'Delete work log', exact: true }).click();
  await deleting.waitFor({ state: 'hidden' });
  await page.getByRole('alert').getByText('The work log was not deleted. Reload the task before trying again.', { exact: true }).waitFor();
  await page.screenshot({ path: resolve(reports, 'refused-worklog-deletion.png'), fullPage: true });
  await page.getByRole('button', { name: 'Reload task', exact: true }).click();
  await page.getByText('Concurrent browser work log', { exact: true }).waitFor({ state: 'detached' });

  const lookup = await context.newPage();
  observe(lookup);
  await lookup.goto(base + '/workops/tasks');
  const targetKey = await lookup.getByRole('row').filter({ hasText: 'Atlas form task' }).getByRole('link').first().textContent();
  assert.ok(targetKey);
  await lookup.close();
  await page.getByRole('button', { name: 'Link task', exact: true }).click();
  const linking = dialog('Link task');
  await linking.waitFor();
  await linking.locator('input[name=target]').fill(targetKey);
  await linking.getByRole('button', { name: 'Link task', exact: true }).click();
  await linking.waitFor({ state: 'hidden' });
  await feedback('Task linked.');
  await page.getByRole('link', { name: new RegExp(`${targetKey}.*Atlas form task`) }).waitFor();

  const affected = page.getByRole('combobox', { name: 'Add affected project', exact: true });
  await affected.click();
  await page.getByRole('option', { name: /Atlas related project/ }).click();
  await page.getByRole('button', { name: 'Add project', exact: true }).click();
  await feedback('Affected project added.');
  await page.getByRole('button', { name: 'Remove Atlas related project from affected projects', exact: true }).click();
  await feedback('Affected project removed.');

  await page.getByRole('button', { name: 'Add document', exact: true }).click();
  await feedback('Document added.');
  const documentEditor = page.locator('[data-workops-document] [data-bml-document-editor]');
  await documentEditor.locator('[contenteditable=true]').first().waitFor({ timeout: 15000 });
  await documentEditor.locator('.ProseMirror > h1').fill('Collaborative task document');
  await documentEditor.locator('.ProseMirror > p').fill('Rich task body');
  await documentEditor.evaluate(element => {
    globalThis.retainedDocument = element;
    globalThis.retainedDocumentSurface = element.querySelector('.ProseMirror');
  });
  const collaborative = await context.newPage();
  observe(collaborative);
  await collaborative.goto(`${peer}/workops/tasks/${key}`);
  const remoteEditor = collaborative.locator('[data-workops-document] [data-document-ready]');
  await remoteEditor.waitFor();
  await remoteEditor.locator('.ProseMirror > h1').filter({ hasText: 'Collaborative task document' }).waitFor();
  await collaborative.bringToFront();
  await remoteEditor.locator('.ProseMirror > p').fill('Rich task body from a second editor');
  await documentEditor.locator('.ProseMirror > p').filter({ hasText: 'Rich task body from a second editor' }).waitFor({ timeout: 5000 });
  const remoteParagraph = remoteEditor.locator('.ProseMirror > p');
  await collaborative.evaluate(() => {
    globalThis.mentionQueries = [];
    document.addEventListener('mentionsearch', event => globalThis.mentionQueries.push({ kind: event.detail.kind, query: event.detail.query }), true);
    document.addEventListener('mentionsearcherror', event => globalThis.mentionQueries.push({ kind: event.detail.kind, query: event.detail.query, error: String(event.detail.error) }), true);
  });
  await remoteEditor.locator('.ProseMirror').focus();
  await collaborative.keyboard.insertText(` @${mentionHandle.slice(0, -1)}`);
  const documentMentions = collaborative.getByRole('listbox', { name: 'Document mentions', exact: true });
  await documentMentions.getByRole('option', { name: /Mention Candidate/ }).waitFor();
  await remoteEditor.locator('.ProseMirror').press('Enter');
  await documentEditor.locator('.document-mention', { hasText: '@Mention Candidate' }).waitFor({ timeout: 5000 });
  for (const [query, label] of [['mention', 'Atlas mention document'], ['mention', 'Atlas mention collection']]) {
    await collaborative.bringToFront();
    await remoteEditor.locator('.ProseMirror').focus();
    await collaborative.keyboard.insertText(` @${query}`);
    await documentMentions.getByRole('option', { name: new RegExp(`^${label}`) }).click();
    await documentEditor.locator('.document-mention', { hasText: `@${label}` }).waitFor({ timeout: 5000 });
  }
  await page.bringToFront();
  await page.getByRole('button', { name: 'Save document', exact: true }).click();
  await feedback('Document saved.');
  assert.equal(await documentEditor.evaluate(element => element === globalThis.retainedDocument && element.querySelector('.ProseMirror') === globalThis.retainedDocumentSurface), true, 'saving preserves the active shared editor');
  await collaborative.waitForFunction(() => !document.querySelector('[data-workops-document] [data-document-dirty]'), null, { timeout: 5000 });
  await collaborative.close();
  const persisted = await page.evaluate(async () => {
    const id = document.querySelector('[data-bml-document-editor]').dataset.collaborationRoom.split('.')[0];
    const response = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` },
      body: JSON.stringify({ query: `{ content { metadata(id:"${id}") { id document { title content } } } }` }) });
    return response.json();
  });
  assert.equal(persisted.errors, undefined, JSON.stringify(persisted));
  assert.equal(persisted.data.content.metadata.document.title, 'Collaborative task document');
  const savedParagraph = persisted.data.content.metadata.document.content.document.content[1].content;
  assert.equal(savedParagraph[0].text, 'Rich task body from a second editor ');
  assert.equal(savedParagraph[1].type, 'mention');
  assert.equal(savedParagraph[1].attrs.label, 'Mention Candidate');
  assert.equal(savedParagraph[1].attrs.entityType, 'profile');
  assert.equal(savedParagraph[1].attrs.mentionSuggestionChar, '@');
  assert.match(savedParagraph[1].attrs.id, /^[a-f0-9-]{36}$/);
  const savedMentions = savedParagraph.filter(node => node.type === 'mention').map(node => node.attrs);
  assert.deepEqual(savedMentions.map(mention => mention.entityType), ['profile', 'metadata', 'collection']);
  assert.equal(savedMentions[1].id, process.env.ATLAS_WORKOPS_TEST_MENTION_METADATA);
  assert.equal(savedMentions[2].id, process.env.ATLAS_WORKOPS_TEST_MENTION_COLLECTION);
  await documentTemplate({ page, context, peer, key, reports, observe });
  await documentReferences({ page, context, connection: owned.connection, base, peer, key, reports, observe });
  await documentImages({ page, context, connection: owned.connection, base, peer, key, reports, observe });
  const readerContext = await owned.connection.newContext(), readerPage = await readerContext.newPage();
  observe(readerPage);
  const readerSockets = [];
  readerPage.on('websocket', socket => { if (new URL(socket.url()).pathname === '/collaboration') readerSockets.push(socket.url()); });
  try {
    await readerPage.goto(`${base}/workops/tasks/${key}`);
    await readerPage.waitForURL(url => url.pathname === '/login');
    await readerPage.locator('input[name=identifier]').fill(process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER);
    await readerPage.locator('input[name=password]').fill(process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD);
    await readerPage.locator('button[type=submit]').click();
    await readerPage.waitForURL(url => url.pathname.includes(`/tasks/${key}`));
    try { await readerPage.locator('[data-workops-document]').getByText('The document is unavailable.', { exact: true }).waitFor({ timeout: 10000 }); }
    catch (error) {
      const identity = await readerPage.evaluate(async id => {
        const response = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` },
          body: JSON.stringify({ query: `{ security { principals { current { id groups { id type } } } } content { readable:checkMetadataActions(actions:[VIEW],ids:["${id}"]) editable:checkMetadataActions(actions:[EDIT],ids:["${id}"]) } }` }) });
        return response.json();
      }, persisted.data.content.metadata.id);
      throw new Error(`${error.message}\nReader page:\n${await readerPage.locator('body').innerText()}\nReader permissions:\n${JSON.stringify(identity)}`);
    }
    assert.equal(await readerPage.getByText('Rich task body from a second editor', { exact: true }).count(), 0, 'task permissions do not expose an inaccessible Content document');
    const granted = await page.evaluate(async ({ id, group }) => {
      const response = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` },
        body: JSON.stringify({ query: `mutation { content { metadata { addPermission(permission:{entityId:"${id}",groupId:"${group}",action:VIEW}) { action groupId } } } }` }) });
      return response.json();
    }, { id: persisted.data.content.metadata.id, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
    assert.equal(granted.errors, undefined, JSON.stringify(granted));
    assert.equal(granted.data.content.metadata.addPermission.action, 'VIEW');
    assert.equal(granted.data.content.metadata.addPermission.groupId, process.env.ATLAS_WORKOPS_TEST_READER_GROUP);
    await readerPage.reload();
    const readOnlyDocument = readerPage.locator('[data-workops-document] [data-document-ready]');
    await readOnlyDocument.waitFor();
    await readOnlyDocument.locator('.ProseMirror > p').filter({ hasText: 'Rich task body from a second editor' }).waitFor();
    assert.equal(await readOnlyDocument.locator('.ProseMirror').getAttribute('contenteditable'), 'false');
    await readOnlyDocument.locator('.document-diagram svg').waitFor();
    assert.equal(await readOnlyDocument.locator('[data-document-code] .cm-content').isVisible(), false);
    assert.equal(await readOnlyDocument.locator('.document-code-header').isVisible(), false);
    assert.equal(await readOnlyDocument.getByRole('button', { name: 'Remove Notes container', exact: true }).count(), 0);
    const collapse = readOnlyDocument.getByRole('button', { name: 'Collapse Notes container', exact: true });
    await collapse.click();
    assert.equal(await readOnlyDocument.locator('[data-document-container=notes] .document-container-content').isVisible(), false);
    assert.equal(await readerPage.getByRole('button', { name: 'Save document', exact: true }).count(), 0);
    assert.deepEqual(readerSockets, [], 'a read-only document does not open an edit-authorized collaboration socket');
    const denied = await readerPage.evaluate(async id => {
      const response = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` },
        body: JSON.stringify({ query: `mutation { content { metadata { setMetadataDocument(id:"${id}",version:1,document:{title:"Denied edit",content:{document:{type:"doc"}}}) } } }` }) });
      return response.json();
    }, persisted.data.content.metadata.id);
    assert.ok(denied.errors?.some(error => error.message === 'Unauthorized access'), JSON.stringify(denied));
    await readerPage.screenshot({ path: resolve(reports, 'read-only-document.png'), fullPage: true });
    await referenceDetails({ admin: page, reader: readerPage, connection: owned.connection, base, key,
      mentions: savedMentions, reports, observe });
    const revoked = await page.evaluate(async ({ id, group }) => {
      const response = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}` },
        body: JSON.stringify({ query: `mutation { content { metadata { deletePermission(permission:{entityId:"${id}",groupId:"${group}",action:VIEW}) { action groupId } } } }` }) });
      return response.json();
    }, { id: persisted.data.content.metadata.id, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
    assert.equal(revoked.errors, undefined, JSON.stringify(revoked));
    assert.equal(revoked.data.content.metadata.deletePermission.action, 'VIEW');
    assert.equal(revoked.data.content.metadata.deletePermission.groupId, process.env.ATLAS_WORKOPS_TEST_READER_GROUP);
    await readerPage.getByRole('button', { name: /^(Watch|Unwatch) \(/ }).click();
    await readerPage.locator('[data-workops-document]').getByText('The document is unavailable.', { exact: true }).waitFor({ timeout: 5000 });
    assert.equal(await readerPage.getByText('Rich task body from a second editor', { exact: true }).count(), 0);
  } finally { await readerContext.close(); }

  for (const appearance of ['dark', 'light']) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]);
    for (const width of [1920, 1024, 768, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      for (const tab of ['comments', 'history', 'worklog']) {
        await page.goto(`${base}/workops/tasks/${key}?tab=${tab}`);
        await page.getByRole('heading', { name: 'Task detail browser acceptance', exact: true }).waitFor();
        await page.locator('[data-workops-document] [data-document-ready]').waitFor();
        assert.equal(await page.locator('html').getAttribute('data-theme'), appearance);
        await checkStartDate();
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
        assert.equal(overflow, false, `${appearance} ${width} ${tab}`);
        await page.screenshot({ path: resolve(reports, `${appearance}-${width}-${tab}.png`), fullPage: true });
      }
      await startDate().scrollIntoViewIfNeeded();
      await page.screenshot({ path: resolve(reports, `${appearance}-${width}-details.png`), fullPage: true });
      const form = await edit(page);
      await form.locator('input[name=summary]').fill(`Draft at ${width}`);
      const geometry = await form.evaluate(element => {
        const box = element.getBoundingClientRect();
        return { left: box.left, right: box.right, top: box.top, bottom: box.bottom, width: innerWidth, height: innerHeight, background: getComputedStyle(element).backgroundColor };
      });
      assert.ok(geometry.left >= -1 && geometry.right <= geometry.width + 1 && geometry.top >= -1 && geometry.bottom <= geometry.height + 1, JSON.stringify(geometry));
      assert.equal(geometry.background, appearance === 'light' ? 'rgb(255, 255, 255)' : 'rgb(16, 19, 28)');
      await page.screenshot({ path: resolve(reports, `${appearance}-${width}-edit.png`), fullPage: true });
      await form.locator('input[name=summary]').press('Escape');
      await form.waitFor({ state: 'hidden' });
      await page.waitForFunction(() => !document.querySelector('[data-bml-action-pending]'));
      assert.equal(await page.getByRole('button', { name: 'Edit task', exact: true }).evaluate(element => element === document.activeElement), true);
    }
  }
  assert.deepEqual(errors, []);
  console.log('WorkOps task detail actions, stale drafts, activity and responsive palettes passed.');
} catch (error) {
  await page.screenshot({ path: resolve(reports, 'failure.png'), fullPage: true }).catch(() => {});
  console.error(await page.locator('body').innerText().catch(() => 'Page unavailable'));
  console.error(JSON.stringify(errors));
  console.error('Mention responses', JSON.stringify(mentionResponses));
  for (const tab of context.pages()) console.error('Mention state', JSON.stringify(await tab.evaluate(() => ({
    active: document.activeElement?.outerHTML.slice(0, 300), queries: globalThis.mentionQueries,
    editors: [...document.querySelectorAll('[data-bml-document-editor]')].map(root => ({
      mentions: root.hasAttribute('data-mentions'), text: root.querySelector('.tiptap')?.textContent,
      focused: root.querySelector('.tiptap')?.editor?.isFocused, menu: root.querySelector('.bml-mention-menu')?.outerHTML,
    })),
  })).catch(() => ({}))));
  throw error;
} finally { await owned.close(); }
