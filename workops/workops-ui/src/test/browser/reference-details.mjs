import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

/** Exercise actual mention links with the isolated fixture's administrator and restricted reader. */
export async function referenceDetails({ admin, reader, connection, base, key, mentions, reports, observe }) {
  const query = async (page, source) => {
    const response = await page.evaluate(async source => {
      const result = await fetch('/graphql', { method: 'POST', headers: {
        'Content-Type': 'application/json', Authorization: `Bearer ${await globalThis.boscaAuth.getToken()}`,
      }, body: JSON.stringify({ query: source }) });
      return result.json();
    }, source);
    assert.equal(response.errors, undefined, JSON.stringify(response));
    return response.data;
  };
  const collection = mentions.find(item => item.entityType === 'collection');
  const metadata = mentions.find(item => item.entityType === 'metadata');
  const profile = mentions.find(item => item.entityType === 'profile');
  const group = process.env.ATLAS_WORKOPS_TEST_READER_GROUP;
  for (let index = 0; index < 26; index++) await query(admin, `mutation { content { collection {
    add(collection:{name:"Reference child ${String(index).padStart(2, '0')}",parentCollectionId:"${collection.id}"}) { id }
  } } }`);
  const adminRef = await admin.context().newPage(), readerRef = await reader.context().newPage();
  observe(adminRef); observe(readerRef);
  const sockets = [];
  for (const page of [adminRef, readerRef]) page.on('websocket', socket => {
    if (new URL(socket.url()).pathname === '/collaboration') sockets.push(socket.url());
  });
  const origin = `/workops/tasks/${key}?tab=history`;
  const reference = item => `/workops/references/${item.entityType}/${item.id}`;
  const navigate = async (page, item) => {
    await page.goto(base + reference(item));
    await page.locator('[data-workops-reference]').waitFor();
  };
  try {
    // A readonly task document resolves all three stored mention kinds inside the current Atlas host.
    for (const item of mentions) {
      await readerRef.goto(base + origin);
      await readerRef.locator('[data-workops-document] [data-document-ready]').waitFor();
      await readerRef.locator(`[data-mention-id="${item.id}"]`).click();
      await readerRef.waitForURL(url => url.pathname === reference(item));
      assert.equal(new URL(readerRef.url()).origin, base);
      assert.equal(new URL(readerRef.url()).searchParams.get('returnTo'), origin);
      await readerRef.locator('.page-heading').getByRole('heading', { name: item.label, exact: true }).waitFor();
      assert.doesNotMatch(await readerRef.locator('body').innerText(), /[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}/i);
      if (item === metadata) {
        const editor = readerRef.locator('[data-bml-document-editor][data-document-ready]');
        await editor.locator('.ProseMirror').getByText('A reference target', { exact: true }).waitFor();
        assert.equal(await editor.locator('.ProseMirror').getAttribute('contenteditable'), 'false');
        assert.equal(await readerRef.getByRole('button', { name: 'Save document', exact: true }).count(), 0);
      } else if (item === profile) {
        await readerRef.getByText(`@${process.env.ATLAS_WORKOPS_TEST_MENTION_HANDLE}`, { exact: true }).waitFor();
      } else {
        await readerRef.getByText('This collection has no visible items.', { exact: true }).waitFor();
      }
      await readerRef.getByRole('link', { name: 'Back to Work Ops', exact: true }).click();
      await readerRef.waitForURL(url => url.pathname === `/workops/tasks/${key}` && url.searchParams.get('tab') === 'history');
    }
    // The owning collection API supplies LIST and child VIEW filtering, including offset pages.
    await navigate(adminRef, collection);
    const childNames = new Set(await adminRef.locator('tbody a').allTextContents());
    assert.equal(childNames.size, 25);
    await adminRef.getByRole('link', { name: 'Next', exact: true }).click();
    await adminRef.waitForURL(url => url.searchParams.get('offset') === '25');
    const lastPage = await adminRef.locator('tbody a').allTextContents();
    assert.equal(lastPage.length, 1); lastPage.forEach(name => childNames.add(name));
    assert.equal(childNames.size, 26);
    assert.equal(await adminRef.getByRole('link', { name: 'Next', exact: true }).count(), 0);
    await adminRef.getByRole('link', { name: 'Previous', exact: true }).click();
    await adminRef.waitForURL(url => url.searchParams.get('offset') === '0');
    await adminRef.locator('tbody a').first().click();
    await adminRef.locator('.page-heading').getByRole('heading', { name: /^Reference child / }).waitFor();

    // File references use the owning storage service's authorized URLs, including actual bytes.
    const files = [
      { name: 'Reference text.txt', type: 'text/plain', body: 'A referenced text file.\n' },
      { name: 'Reference image.svg', type: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="320" height="120"><rect width="320" height="120" fill="white"/><text x="20" y="65" font-size="20" fill="#111">Reference preview</text></svg>' },
    ];
    for (const file of files) {
      const created = await query(admin, `mutation { content { metadata {
        add(metadata:{name:${JSON.stringify(file.name)},contentType:${JSON.stringify(file.type)},languageTag:"en"}) { id }
      } } }`);
      const id = created.content.metadata.add.id;
      await query(admin, `mutation { content { metadata {
        setMetadataTextContents(id:"${id}",contentType:${JSON.stringify(file.type)},content:${JSON.stringify(file.body)})
        addPermission(permission:{entityId:"${id}",groupId:"${group}",action:VIEW}) { action }
      } } }`);
      await navigate(readerRef, { entityType: 'metadata', id });
      await readerRef.locator('.page-heading').getByRole('heading', { name: file.name, exact: true }).waitFor();
      await readerRef.getByText(file.type, { exact: true }).waitFor();
      const link = readerRef.getByRole('link', { name: 'Download', exact: true });
      const href = await link.getAttribute('href');
      const storageUrl = new URL(href);
      const isolatedHost = ['localhost', '127.0.0.1'].includes(storageUrl.hostname) ||
        (process.env.ATLAS_FIXTURE_EXTERNAL_HOST === 'host.docker.internal' &&
          storageUrl.hostname === 'host.docker.internal' &&
          storageUrl.port === new URL(process.env.ATLAS_WORKOPS_TEST_API_URL).port);
      assert.ok(isolatedHost, 'Storage URLs must use the isolated fixture.');
      if (file.type.startsWith('image/')) {
        await readerRef.waitForFunction(() => {
          const image = document.querySelector('[data-workops-reference] img');
          return image?.complete && image.naturalWidth === 320;
        });
        await readerRef.screenshot({ path: resolve(reports, 'reference-image.png'), fullPage: true });
      }
      const completed = readerRef.waitForEvent('download');
      await link.click();
      const download = await completed;
      assert.equal(download.suggestedFilename(), file.name);
      const path = resolve(reports, file.name); await download.saveAs(path);
      assert.equal(await readFile(path, 'utf8'), file.body);
      assert.equal(new URL(readerRef.url()).pathname, `/workops/references/metadata/${id}`);
    }

    // Reloads must clear previously visible details when the current identity loses access.
    for (const item of [metadata, collection]) {
      await navigate(readerRef, item);
      await readerRef.locator('.page-heading').getByRole('heading', { name: item.label, exact: true }).waitFor();
      await query(admin, `mutation { content { ${item.entityType} {
        deletePermission(permission:{entityId:"${item.id}",groupId:"${group}",action:VIEW}) { action }
      } } }`);
      try {
        await readerRef.reload();
        await readerRef.locator('.page-heading').getByRole('heading', { name: 'Reference unavailable', exact: true }).waitFor();
        assert.equal(await readerRef.getByText(item.label, { exact: true }).count(), 0);
        assert.equal(await readerRef.getByText('A reference target', { exact: true }).count(), 0);
      } finally {
        await query(admin, `mutation { content { ${item.entityType} {
          addPermission(permission:{entityId:"${item.id}",groupId:"${group}",action:VIEW}) { action }
        } } }`);
      }
      await readerRef.reload();
      await readerRef.locator('.page-heading').getByRole('heading', { name: item.label, exact: true }).waitFor();
    }
    await query(admin, `mutation { profiles { edit(id:"${profile.id}",profile:{name:"Mention Candidate",visibility:USER,attributes:[]}) { id } } }`);
    try {
      await navigate(readerRef, profile);
      await readerRef.locator('.page-heading').getByRole('heading', { name: 'Reference unavailable', exact: true }).waitFor();
      assert.equal(await readerRef.getByText('Mention Candidate', { exact: true }).count(), 0);
    } finally {
      await query(admin, `mutation { profiles { edit(id:"${profile.id}",profile:{name:"Mention Candidate",visibility:PUBLIC,attributes:[]}) { id } } }`);
    }
    // Both palettes and small screens use the same shared components without horizontal overflow.
    for (const appearance of ['dark', 'light']) {
      await readerRef.context().addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]);
      for (const width of [390, 1024]) {
        await readerRef.setViewportSize({ width, height: 1000 });
        await navigate(readerRef, metadata);
        await readerRef.locator('[data-document-ready]').waitFor();
        assert.equal(await readerRef.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
        await readerRef.screenshot({ path: resolve(reports, `reference-${appearance}-${width}.png`), fullPage: true });
      }
    }
    assert.deepEqual(sockets, [], 'readonly task and reference views never open a collaboration socket');
    const anonymous = await connection.newContext();
    try {
      const page = await anonymous.newPage();
      await page.goto(base + reference(metadata));
      await page.waitForURL(url => url.pathname === '/login');
      assert.ok(new URL(page.url()).searchParams.get('redirect')?.includes(reference(metadata)));
    } finally { await anonymous.close(); }
    console.log('Atlas reference activation, readonly details, offset collections, access loss and auth passed.');
  } catch (error) {
    for (const [label, page] of [['admin', adminRef], ['reader', readerRef]]) {
      console.error(`Reference ${label}:`, await page.locator('body').innerText().catch(() => 'Page unavailable'));
      await page.screenshot({ path: resolve(reports, `reference-${label}-failure.png`), fullPage: true }).catch(() => {});
    }
    throw error;
  } finally { await adminRef.close(); await readerRef.close(); }
}
