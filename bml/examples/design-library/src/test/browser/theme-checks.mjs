import assert from 'node:assert/strict';

/** Ordinary consumer CSS changes derived values without touching the compiled library. */
export async function checkThemes({ call, evaluate, until, base }) {
  const css = (name, property) => `getComputedStyle(document.querySelector('[data-token-${name}=custom]')).${property}`;
  const metrics = id => evaluate(`(() => {
    const get = name => document.querySelector('[data-token-' + name + '=${id}]');
    const input = get('input'), button = get('button'), icon = get('icon');
    return { heights: ['button', 'small', 'xs', 'input'].map(name => get(name).getBoundingClientRect().height),
      icon: [icon.getBoundingClientRect().width, icon.getBoundingClientRect().height],
      background: getComputedStyle(input).backgroundColor, radius: getComputedStyle(input).borderTopLeftRadius,
      gap: getComputedStyle(button.querySelector('.button-content')).gap,
      disabled: get('disabled').disabled, loading: get('loading').disabled && get('loading').getAttribute('aria-busy') === 'true' };
  })()`);
  const color = expression => evaluate(`(() => {
    const canvas = document.createElement('canvas'); canvas.width = canvas.height = 1;
    const ctx = canvas.getContext('2d'); ctx.fillStyle = ${expression}; ctx.fillRect(0, 0, 1, 1);
    return Array.from(ctx.getImageData(0, 0, 1, 1).data);
  })()`);
  const shadow = selector => color(`getComputedStyle(document.querySelector(${JSON.stringify(selector)})).boxShadow.match(/(?:rgba?|color)[(][^)]*[)]/)[0]`);
  await call('Page.navigate', { url: base + '/themes' });
  await until(`document.readyState === 'complete' && document.querySelector('[data-token-select=custom]').closest('[data-bml-design-select]').querySelector('.select-trigger')`);
  const original = await metrics('default');
  assert.deepEqual(original, { heights: [32, 30, 24, 32], icon: [30, 30], background: 'rgb(22, 26, 37)',
    radius: '9px', gap: '6px', disabled: true, loading: true });
  assert.deepEqual(await metrics('custom'), { heights: [40, 36, 28, 40], icon: [36, 36],
    background: 'rgb(224, 242, 254)', radius: '13px', gap: '10px', disabled: true, loading: true });
  assert.equal(await evaluate(css('input', 'colorScheme')), 'light');
  await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Tab', windowsVirtualKeyCode: 9 });
  await call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Tab', windowsVirtualKeyCode: 9 });
  await evaluate(`document.querySelector('[data-token-button=custom]').focus()`);
  assert.deepEqual(await evaluate(`[${css('button', 'outlineWidth')}, ${css('button', 'outlineOffset')}, ${css('button', 'outlineColor')}]`),
    ['3px', '4px', 'rgb(3, 105, 161)']);
  await evaluate(`document.querySelector('[data-token-popover=custom] > summary').click()`);
  const popup = '[data-token-popover=custom] > [data-bml-ref=content]';
  await until(`document.querySelector('${popup}').matches(':popover-open')`);
  assert.deepEqual(await shadow(popup), await color('"rgba(22, 78, 99, 0.4)"'));
  await evaluate(`document.querySelector('[data-token-theme=custom]').classList.add('alternate')`);
  assert.equal(await evaluate(css('input', 'backgroundColor')), 'rgb(220, 252, 231)');
  assert.equal(await evaluate(css('input', 'borderTopLeftRadius')), '15px');
  assert.deepEqual(await shadow(popup), await color('"rgba(22, 101, 52, 0.4)"'));
  assert.deepEqual(await metrics('default'), original, 'subtree changes must not affect its neighbor');
  await evaluate(`document.querySelector('[data-token-popover=custom] > summary').click();
    document.querySelector('[data-token-dialog=custom] > [data-bml-ref=trigger]').click()`);
  const modal = '[data-token-dialog=custom] > dialog';
  await until(`document.querySelector('${modal}').matches(':modal')`);
  assert.deepEqual(await shadow(modal), await color('"rgba(22, 101, 52, 0.5333333333)"'));
  assert.deepEqual(await color(`getComputedStyle(document.querySelector('${modal}'), '::backdrop').backgroundColor`),
    await color('"rgba(22, 101, 52, 0.6)"'));
  await evaluate(`document.querySelector('[data-token-theme=custom]').classList.remove('alternate')`);
  assert.deepEqual(await color(`getComputedStyle(document.querySelector('${modal}'), '::backdrop').backgroundColor`),
    await color('"rgba(22, 78, 99, 0.6)"'));
  await evaluate(`document.querySelector('${modal}').close(); document.querySelector('[data-token-theme=custom]').classList.add('explicit-controls')`);
  assert.equal(await evaluate(css('input', 'backgroundColor')), 'rgb(254, 243, 199)');
  assert.equal(await evaluate(css('input', 'borderTopLeftRadius')), '5px');
  assert.deepEqual(await metrics('default'), original);
  await call('Emulation.setScriptExecutionDisabled', { value: true });
  try {
    await call('Page.navigate', { url: base + '/themes' });
    await until(`document.readyState === 'complete' && document.querySelector('[data-token-select=custom]')`);
    assert.ok(await evaluate(`(() => { const select = document.querySelector('[data-token-select=custom]');
      return !select.classList.contains('enhanced') && select.getBoundingClientRect().height === 40 &&
        getComputedStyle(select).backgroundColor === 'rgb(224, 242, 254)'; })()`));
  } finally { await call('Emulation.setScriptExecutionDisabled', { value: false }); }
}
