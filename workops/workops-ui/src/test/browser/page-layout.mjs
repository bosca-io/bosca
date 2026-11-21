import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname), 'Use isolated local Atlas data.');
const reports = resolve('build/reports/page-layout-browser', new URL(base).port);
await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0);
const context = await owned.connection.newContext(), page = await context.newPage(), errors = [];
page.on('pageerror', error => errors.push(error.message));
const actions = async names => {
  const heading = page.locator('.page-heading').first(), title = heading.locator('h1');
  for (const name of names) {
    const button = heading.getByRole('button', { name, exact: true });
    await button.waitFor();
    const box = await button.boundingBox(), header = await heading.boundingBox(), text = await title.boundingBox();
    assert.ok(box && header && text);
    assert.ok(box.y >= header.y && box.y + box.height <= header.y + header.height + 1, `${name} stays in the header`);
    if (page.viewportSize().width >= 1024) assert.ok(box.x >= text.x + text.width, `${name} belongs to the right of the title`);
  }
  for (const button of await page.getByRole('button', { name: /^Refresh / }).all()) {
    assert.equal((await button.innerText()).trim(), '', 'Refresh is an icon action');
    assert.equal(await button.locator('svg').count(), 1);
    assert.equal(await button.getAttribute('data-tooltip-content'), await button.getAttribute('aria-label'));
  }
  for (const helper of await page.locator('p.help-text').all()) {
    assert.deepEqual(await helper.evaluate(node => [getComputedStyle(node).fontSize, getComputedStyle(node).fontWeight]), ['12px', '400']);
  }
  for (const button of await page.locator('table').getByRole('button', { name: /^(Edit |Delete |Close |Start |Complete |View |Move |Create deploy config for )/ }).all()) {
    assert.equal((await button.innerText()).trim(), '', 'Repeated row operations use icons');
    assert.equal(await button.locator('svg').count(), 1);
  }
  for (const caption of await page.locator('table caption:not(.sr-only)').all()) {
    const text = (await caption.innerText()).trim();
    assert.equal(await page.getByRole('heading', { name: text, exact: true, level: 2 }).count(), 0, `List title is not repeated: ${text}`);
  }
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, 'The page fits the viewport');
};
try {
  await page.goto(base + '/workops/tasks');
  await page.locator('input[name=identifier]').fill('admin');
  await page.locator('input[name=password]').fill(process.env.ATLAS_WORKOPS_TEST_PASSWORD);
  await page.locator('button[type=submit]').click();
  await page.waitForURL(url => url.pathname !== '/login');
  for (const appearance of ['dark', 'light']) for (const width of [1920, 1024, 768, 390]) {
    await context.addCookies([{ name: 'git_ui_appearance', value: appearance, url: base }]);
    await page.setViewportSize({ width, height: 900 });
    for (const [path, names] of [
      ['tasks', ['New task']], ['specs', ['New specification']],
      ['inbox', ['Refresh inbox', 'Mark all read']], ['boards', ['New board']],
      ['milestones', ['New milestone', 'Refresh milestones']], ['settings/milestones', ['New milestone', 'Refresh milestones']],
      ['sprints', []], ['releases', ['Build release', 'Refresh releases']],
      ['releases/versions', ['New version', 'Refresh versions']], ['settings/components', ['New component', 'Refresh components']],
      ['settings/automation', ['New rule', 'Refresh rules']], ['settings/permissions', ['Add permission', 'Refresh permissions']],
      ['settings/notifications', ['Refresh schemes']], ['settings/notifications?tab=preferences', ['Save preferences', 'Refresh preferences']],
    ]) {
      await page.goto(`${base}/workops/${path}`);
      await actions(names);
      if (path === 'tasks' || path === 'specs') {
        const editor = page.locator('[data-bml-code-editor]');
        await editor.locator('.cm-content').waitFor();
        assert.equal(await editor.locator('.cm-gutters').count(), 0, 'Queries have no code-file gutter');
        assert.ok((await editor.boundingBox()).height <= 60, 'A one-line query stays compact');
        const layout = await editor.evaluate(node => {
          const editor = node.getBoundingClientRect(), row = node.closest('.workops-query-row'), search = row.querySelector('button[type=submit]').getBoundingClientRect();
          return { editor: { x: editor.x, right: editor.right, y: editor.y }, search: { x: search.x, y: search.y } };
        });
        if (width >= 1024) {
          assert.ok(layout.search.x >= layout.editor.right && Math.abs(layout.search.y - layout.editor.y) < 12, 'Search stays beside the compact query');
          const rowWidth = await editor.locator('xpath=ancestor::div[contains(@class,"workops-query-row")]').evaluate(node => node.clientWidth);
          assert.ok((await editor.boundingBox()).width > rowWidth / 2, 'The query uses the available search width');
        }
        for (const name of ['Clear query', 'Search']) {
          const button = page.getByRole('button', { name, exact: true });
          assert.equal((await button.innerText()).trim(), '', `${name} uses an icon without visible text`);
          assert.equal(await button.getAttribute('data-tooltip-content'), name, `${name} retains its tooltip`);
          assert.equal(await button.locator('svg').count(), 1, `${name} has a visible icon`);
        }
        await page.getByRole('button', { name: 'Search', exact: true }).focus();
        const unfocused = await editor.evaluate(node => getComputedStyle(node).borderColor);
        await page.screenshot({ path: resolve(reports, `${path}-${appearance}-${width}-unfocused.png`), fullPage: true });
        await page.keyboard.press('/');
        assert.equal(await editor.locator('.cm-content').evaluate(node => node === document.activeElement), true);
        const focus = await editor.evaluate(node => ({
          border: getComputedStyle(node).borderColor, shadow: getComputedStyle(node).boxShadow,
          innerOutline: getComputedStyle(node.querySelector('.cm-editor')).outlineStyle,
        }));
        assert.notEqual(focus.border, unfocused, 'Focus is indicated on the existing rounded control border');
        assert.notEqual(focus.shadow, 'none', 'Keyboard focus remains visible');
        assert.equal(focus.innerOutline, 'none', 'Focus adds no inner rectangular box');
      }
      await page.screenshot({ path: resolve(reports, `${path.replaceAll("?", "-")}-${appearance}-${width}.png`), fullPage: true });
    }
  }
  await page.setViewportSize({ width: 1280, height: 900 });
  for (const [path, name] of [
    ['portfolios', 'New portfolio'], ['programs', 'New program'], ['projects', 'New project'],
    ['filters', 'New filter'], ['milestones', 'New milestone'], ['settings/statuses', 'New status'],
    ['settings/priorities', 'New priority'], ['settings/task-types', 'New task type'],
    ['settings/labels', 'New label'], ['settings/resolutions', 'New resolution'],
    ['settings/link-types', 'New link type'], ['settings/environment-types', 'New environment type'],
    ['settings/automation', 'New rule'], ['settings/workflows', 'New workflow'], ['settings/sla', 'New SLA policy'],
  ]) {
    await page.goto(`${base}/workops/${path}`);
    await actions([name]);
  }
  for (const [path, field, label, retained] of [
    ['milestones', 'programId', 'Program'], ['settings/milestones', 'programId', 'Program'],
    ['boards', 'projectId', 'Project'], ['sprints', 'projectId', 'Project'],
    ['releases/versions', 'projectId', 'Project'], ['settings/components', 'projectId', 'Project'],
    ['settings/notifications?tab=schemes', 'schemeId', 'Scheme', ['tab', 'schemes']],
    ...['portfolio', 'program', 'project'].map(kind => [`settings/permissions?kind=${kind}`, 'entityId', kind[0].toUpperCase()+kind.slice(1), ['kind', kind]]),
  ]) {
    const url=new URL(`${base}/workops/${path}`);url.searchParams.set('offset','25');await page.goto(url.href);
    const select=page.locator(`form[method=get] select[name="${field}"]`);
    const choice=await select.locator('option').evaluateAll(options=>options.filter(option=>option.value&&!option.disabled).map(option=>({value:option.value,label:option.label})).at(-1));
    assert.ok(choice, `${path} has an authorized choice`);
    assert.equal(await page.getByRole('button',{name:/^(Show milestones|Choose (project|program|board|scheme|portfolio)|Apply sprint)$/}).count(),0);
    await page.getByRole('combobox',{name:label,exact:true}).click();await page.getByRole('option',{name:choice.label,exact:true}).click();
    await page.waitForURL(url=>url.searchParams.get(field)===choice.value&&!url.searchParams.has('offset'));
    if(retained)assert.equal(new URL(page.url()).searchParams.get(retained[0]),retained[1]);
  }
  assert.deepEqual(errors, []);
  console.log('WorkOps page actions and compact queries passed in both palettes at four widths.');
} finally {
  await context.close();
  await owned.close();
}
