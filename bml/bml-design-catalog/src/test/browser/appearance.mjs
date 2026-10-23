import assert from 'node:assert/strict';

/** Appearance is a visitor preference across real documents, reload and restored history. */
export async function checkAppearance({ navigate, evaluate, until, call }) {
  await call('Network.clearBrowserCookies');
  await navigate('/');
  await until(`document.documentElement.hasAttribute('data-catalog-appearance-ready')`);
  const state = () => evaluate(`({checked:document.querySelector('[data-catalog-light]').checked,
    background:getComputedStyle(document.body).backgroundColor})`);
  assert.deepEqual(await state(), { checked: false, background: 'rgb(10, 12, 18)' });
  await evaluate(`document.querySelector('[data-catalog-light]').click()`);
  await until(`document.cookie.includes('bosca_catalog_appearance=light')`);
  const light = await state();
  assert.equal(light.background, 'rgb(248, 250, 252)');
  // Actual links exercise the same navigation as a visitor, with no fixture reset.
  await evaluate(`document.querySelector('nav a[href="/selection"]').click()`);
  await until(`location.pathname==='/selection'&&document.readyState==='complete'`);
  assert.deepEqual(await state(), light);
  const typography = () => evaluate(`(()=>{const css=getComputedStyle(document.querySelector('.page-intro,.overview-intro p'));
    return [css.fontFamily,css.fontSize,css.fontWeight,css.lineHeight,css.letterSpacing,css.color];})()`);
  const selectionTypography = await typography();
  const html = await evaluate(`fetch('/components/content').then(response=>response.text())`);
  assert.match(html, /<input[^>]*checked[^>]*data-catalog-light|<input[^>]*data-catalog-light[^>]*checked/,
    'The saved appearance is already checked in server HTML');
  await call('Page.reload');
  await until(`document.readyState==='complete'&&Boolean(document.querySelector('[data-catalog-light]'))`);
  assert.deepEqual(await state(), light);
  await evaluate(`document.querySelector('nav a[href="/"]').click()`);
  await until(`location.pathname==='/'&&document.readyState==='complete'`);
  assert.deepEqual(await typography(), selectionTypography, 'Overview and selection introductions share all font settings');
  await evaluate(`document.querySelector('[data-catalog-light]').click()`);
  await until(`document.cookie.includes('bosca_catalog_appearance=dark')`);
  const dark = await state();
  await evaluate('history.back()');
  await until(`location.pathname==='/selection'&&document.readyState==='complete'&&!document.querySelector('[data-catalog-light]').checked`);
  assert.deepEqual(await state(), dark);
  await evaluate('history.forward()');
  await until(`location.pathname==='/'&&document.readyState==='complete'`);
  assert.deepEqual(await state(), dark);
  await navigate('/themes');
  const palettes = () => evaluate(`['dark','light'].map(id=>getComputedStyle(document.querySelector('[data-catalog-palette='+id+'] .ui-card')).backgroundColor)`);
  const originalPalettes = await palettes();
  await evaluate(`document.querySelector('[data-catalog-light]').click()`);
  await until(`document.cookie.includes('bosca_catalog_appearance=light')`);
  assert.deepEqual(await palettes(), originalPalettes);
  await call('Emulation.setScriptExecutionDisabled', { value: true });
  try {
    await navigate('/foundation');
    assert.deepEqual(await state(), light, 'Saved light mode renders without client scripts');
  } finally {
    await call('Emulation.setScriptExecutionDisabled', { value: false });
    await call('Network.clearBrowserCookies');
  }
}
