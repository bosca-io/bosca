import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { contrastSamples } from './presentation.mjs';

// State-colored controls retain their own borders; this contract covers neutral boundaries.
const controls = [
  '.input:not(:disabled)', '.search-input:not(:has(input:disabled))',
  '.button:not(:disabled):not(.primary):not(.danger):not(.ghost)', '.button-link:not(.primary)',
  '.select-widget > select:not(:disabled)', '.select-trigger:not(:disabled)',
  '.select-pagination button:not(:disabled)', '.select-query', '.segmented-control',
  '.comment-editor textarea:not(:disabled)', '.json-editor > textarea:not(:disabled)',
  '.switch-control input:not(:disabled):not(:checked) + .switch-track', '.popover > summary',
  '.cm-panels .cm-textfield', '.cm-panels .cm-button',
].join(', ');

/** Control colors are consumer CSS, independent of divider colors, scripts, and sibling palettes. */
export async function checkControlBorders({ navigate, evaluate, until: poll, call, artifacts, mode }) {
  const until = expression => poll(`Boolean(${expression})`);
  const measurements = [];
  const sample = () => evaluate(`(${contrastSamples.toString()})(':not(*)',null,${JSON.stringify(controls)})`);
  const check = (colors, context) => {
    assert.ok(colors.controls.length > 0, `Missing real controls: ${context}`);
    for (const color of colors.controls) {
      assert.ok(color.ratio >= 3 && color.outsideRatio >= 3, `${context}: ${JSON.stringify(color)}`);
    }
  };
  for (const width of [1440, 390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
    for (const scripts of [true, false]) {
      await call('Emulation.setScriptExecutionDisabled', { value: !scripts });
      for (const appearance of ['dark', 'light']) {
        for (const path of scripts
          ? ['/themes', '/components/controls', '/components/content', '/components/overlays']
          : ['/components/controls', '/components/content']) {
          await call('Network.clearBrowserCookies');
          await navigate(path);
          await evaluate('document.fonts.ready');
          if (scripts && path === '/components/controls') {
            await until(`document.querySelector('select.enhanced')`);
          }
          if (scripts && path === '/components/content') await until(`document.querySelector('[data-source-ready]')`);
          if (appearance === 'light') await evaluate(`document.querySelector('[data-catalog-light]').click()`);
          await evaluate(`document.activeElement.blur();Promise.all(document.getAnimations().filter(a=>a instanceof CSSTransition).map(a=>a.finished)).then(()=>true)`);
          const colors = await sample();
          check(colors, `${width} ${scripts ? 'scripted' : 'native'} ${appearance} ${path}`);
          assert.ok(await evaluate('document.documentElement.scrollWidth<=innerWidth'));
          measurements.push({ width, scripts, appearance, path, controls: colors.controls });
          if (path === '/themes' && appearance === 'dark') {
            const cssContract = await evaluate(`(()=>{
              const root=document.querySelector('[data-catalog-palette=light]'),input=root.querySelector('.input'),
                sibling=document.querySelector('[data-catalog-palette=dark] .input'),card=root.querySelector('.ui-card');
              const originalRoot=root.getAttribute('style'),originalInput=input.getAttribute('style');
              const border=el=>getComputedStyle(el).borderTopColor;
              const before={input:border(input),sibling:border(sibling),surface:border(card)};
              root.style.setProperty('--control-border','rgb(116, 66, 186)');
              const override={input:border(input),sibling:border(sibling),surface:border(card)};
              input.style.setProperty('--control-border','initial');
              input.style.setProperty('--line-2','rgb(105, 115, 140)');
              const fallback=border(input);
              input.focus({preventScroll:true});
              const accent=document.createElement('span');accent.style.color=getComputedStyle(input).getPropertyValue('--accent');
              const focus={border:border(input),accent:accent.style.color};input.blur();
              originalInput===null?input.removeAttribute('style'):input.setAttribute('style',originalInput);
              originalRoot===null?root.removeAttribute('style'):root.setAttribute('style',originalRoot);
              return {before,override,fallback,focus,restored:border(input)};
            })()`);
            assert.equal(cssContract.override.input, 'rgb(116, 66, 186)');
            assert.equal(cssContract.override.sibling, cssContract.before.sibling);
            assert.equal(cssContract.override.surface, cssContract.before.surface);
            assert.equal(cssContract.fallback, 'rgb(105, 115, 140)');
            assert.equal(cssContract.focus.border, cssContract.focus.accent);
            assert.equal(cssContract.restored, cssContract.before.input);
            measurements.at(-1).cssContract = cssContract;
          }
        }
      }
    }
  }
  await call('Emulation.setScriptExecutionDisabled', { value: false });
  await navigate('/source');
  await until(`document.querySelectorAll('[data-source-ready]').length===8`);
  await evaluate(`document.querySelector('[data-catalog-source=light] .cm-content').focus()`);
  for (const type of ['keyDown', 'keyUp']) await call('Input.dispatchKeyEvent', {
    type, key: 'f', windowsVirtualKeyCode: 70, modifiers: await evaluate('/Mac|iPhone|iPad/.test(navigator.platform)?4:2'),
  });
  await until(`document.querySelector('[data-catalog-source=light] .cm-search input[name=search]')===document.activeElement`);
  const sourceControls = await sample();
  check(sourceControls, 'Source-search controls');
  measurements.push({ path: '/source', controls: sourceControls.controls });
  await writeFile(join(artifacts, `catalog-${mode}-control-borders.json`), JSON.stringify(measurements, null, 2));
  console.log(`Catalog ${mode}: neutral control borders, independent palettes, native fallbacks and local CSS overrides passed`);
}
