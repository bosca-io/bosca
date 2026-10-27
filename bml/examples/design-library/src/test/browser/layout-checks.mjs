import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Run only against the caller's isolated design-library fixture browser. */
export async function checkLayout({ call, evaluate, until, base, mode, artifacts }) {
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await call('Page.navigate', { url: base + '/layout' });
  await until('document.readyState === "complete" && document.querySelector("[data-heading-action]")?.dataset.bmlActionBound === "true"');
  await until('document.querySelector(".fixture-nav").scrollLeft > 0');
  const layout = await evaluate(`(() => {
    const header = document.querySelector('[data-app-header]');
    const heading = document.querySelector('[data-page-heading]');
    const mark = header.querySelector('svg');
    const section = document.querySelector('[data-section]');
    return {
      headerWidth: header.getBoundingClientRect().width,
      brand: header.querySelector('.brand-name').textContent, product: header.querySelector('.brand-product').textContent,
      href: header.querySelector('.brand').getAttribute('href'), markView: mark.getAttribute('viewBox'),
      markName: mark.getAttribute('aria-label'), markSize: [mark.getBoundingClientRect().width, mark.getBoundingClientRect().height],
      paths: Array.from(mark.querySelectorAll('path'), path => path.getAttribute('d')),
      fills: Array.from(mark.querySelectorAll('path'), path => getComputedStyle(path).fill),
      trail: Array.from(heading.querySelector('.breadcrumb').children, node => node.textContent.trim()),
      emptyTrail: Array.from(document.querySelector('[data-empty-trail] .breadcrumb').children, node => node.textContent.trim()),
      title: heading.querySelector('h1').textContent, subtitle: heading.querySelector('.heading-subtitle').textContent,
      titleIcon: getComputedStyle(heading.querySelector('.title-mark')).backgroundColor,
      badge: heading.querySelector('.badge').textContent,
      classes: heading.classList.contains('fixture-heading') && document.querySelector('[data-panel]').classList.contains('fixture-panel') && section.classList.contains('fixture-section'),
      propsConsumed: ['trail','icon','badge','subtitle','title'].every(name => !heading.hasAttribute(name)),
      panelRadius: getComputedStyle(document.querySelector('[data-panel]')).borderTopLeftRadius,
      section: section.tagName === 'SECTION' && section.classList.contains('ui-section') && section.hasAttribute('data-bml-c'),
      native: !document.querySelector('[data-native-section]').hasAttribute('data-bml-c'),
      sectionTitle: document.querySelector('[data-section-heading] h2').textContent,
      framed: document.querySelector('[data-disclosure]').hasAttribute('data-framed'),
      details: document.querySelector('#layout-details').open,
    };
  })()`);
  assert.deepEqual(layout, {
    headerWidth: 224, brand: 'Bosca', product: 'Library', href: '/layout', markView: '0 0 512 512', markName: 'Bosca', markSize: [22,22],
    paths: ['M256 262L491 138L256 21L21 138L256 262Z', 'M491 138L256 262V498L491 372V138Z', 'M21 138L256 262V498L21 372V138Z'],
    fills: ['rgb(15, 118, 110)', 'rgb(20, 184, 166)', 'rgb(17, 94, 89)'],
    trail: ['Workspace','/','Components','/','Layout preview'], emptyTrail: ['Standalone heading'],
    title: 'Layout preview', subtitle: 'Shared presentation', titleIcon: 'rgb(21, 128, 61)', badge: 'Preview',
    classes: true, propsConsumed: true, panelRadius: '18px', section: true, native: true,
    sectionTitle: 'Layout details', framed: true, details: false,
  });
  await evaluate('document.querySelector(".fixture-nav").scrollLeft = 0; document.querySelector("[data-nav-update]").click()');
  await until('document.querySelector("[data-nav-update]").textContent.includes("Navigation updates 1")');
  await until('document.querySelector(".fixture-nav").scrollLeft > 0');
  await evaluate('document.querySelector("[data-heading-action]").click()');
  await until('document.querySelector("[data-heading-action]").textContent.includes("Count 1")');
  assert.match(await evaluate('document.querySelector("[data-section-action]").textContent'), /Count 1/);
  await until('document.querySelector(".fixture-nav").scrollLeft > 0');
  await evaluate('document.querySelector("[data-section-action]").click()');
  await until('document.querySelector("[data-heading-action]").textContent.includes("Count 2")');
  const closed = await evaluate('getComputedStyle(document.querySelector(".disclosure-chevron")).transform');
  await evaluate('document.querySelector("[data-disclosure]").click()');
  assert.equal(await evaluate('document.querySelector("#layout-details").open'), true);
  const open = await evaluate('getComputedStyle(document.querySelector(".disclosure-chevron")).transform');
  assert.notEqual(open, closed);
  assert.equal(open, 'matrix(1, 0, 0, 1, 0, 0)');
  await evaluate('document.querySelector("[data-disclosure]").focus()');
  assert.equal(await evaluate('document.activeElement === document.querySelector("[data-disclosure]")'), true);
  await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, text: '\r' });
  await call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });
  assert.equal(await evaluate('document.querySelector("#layout-details").open'), false);
  for (const width of [1440, 390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false });
    await until(`(() => { const nav=document.querySelector('.fixture-nav'); const active=nav.querySelector('[aria-current=page]');
      const outer=nav.getBoundingClientRect(); const item=active.getBoundingClientRect(); return item.left >= outer.left - 1 && item.right <= outer.right + 1 })()`);
    assert.ok(await evaluate('document.documentElement.scrollWidth <= innerWidth'), `Layout overflow at ${width}px`);
    assert.ok(await evaluate(`(()=>{const header=document.querySelector('[data-long-section-heading]'),bounds=header.getBoundingClientRect();return [...header.querySelectorAll('h2,p,button')].every(node=>node.getBoundingClientRect().right<=bounds.right+1);})()`),'Long section titles, subtitles and projected actions fit their container');
    const measured = await evaluate(`(() => {
      const header=document.querySelector('[data-app-header]').getBoundingClientRect();
      const main=document.querySelector('main').getBoundingClientRect();
      return { headerRight:header.right, headerBottom:header.bottom, mainLeft:main.left, mainTop:main.top };
    })()`);
    if (width === 390) assert.ok(measured.mainTop >= measured.headerBottom && measured.mainLeft === 0, JSON.stringify(measured));
    else assert.ok(measured.mainLeft >= measured.headerRight, JSON.stringify(measured));
    const shot = await call('Page.captureScreenshot', { format: 'png' });
    await writeFile(join(artifacts, `layout-${mode}-${width}.png`), Buffer.from(shot.data, 'base64'));
  }
}
