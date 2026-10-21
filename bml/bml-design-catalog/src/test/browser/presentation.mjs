import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Measures the rendered examples, including translucent badge backgrounds and themed native controls. */
export function contrastSamples(textSelector = '.catalog-nav a, .catalog-link, .catalog-description, .example-note, .eyebrow, .catalog-footer, .switch-copy strong, .switch-copy small, .field > span, .field small, .catalog-title h1, .catalog-title p, button:not(:disabled) .button-content, .badge, [data-catalog-name], [data-catalog-error]', foregroundPseudo = null, controlSelector = '[data-catalog-name]') {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = 1;
  const context = canvas.getContext('2d', { willReadFrequently: true });
  const rgba = color => {
    context.clearRect(0, 0, 1, 1);
    context.fillStyle = color;
    context.fillRect(0, 0, 1, 1);
    return [...context.getImageData(0, 0, 1, 1).data];
  };
  const over = (front, back) => front.slice(0, 3).map((value, index) => value * front[3] / 255 + back[index] * (1 - front[3] / 255)).concat(255);
  const background = element => {
    if (!element) return [255, 255, 255, 255];
    const color = rgba(getComputedStyle(element).backgroundColor);
    return color[3] === 255 ? color : over(color, background(element.parentElement));
  };
  const luminance = color => color.slice(0, 3).map(value => value / 255)
    .map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4)
    .reduce((sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index], 0);
  const contrast = (first, second) => {
    const values = [luminance(first), luminance(second)].sort((a, b) => a - b);
    return (values[1] + 0.05) / (values[0] + 0.05);
  };
  const text = [...document.querySelectorAll(textSelector)]
    .filter(element => element.checkVisibility() && !element.closest(':disabled, [inert]'))
    .map(element => ({ label: element.textContent.trim() || element.getAttribute('aria-label') || element.name,
      ratio: contrast(over(rgba(getComputedStyle(element, foregroundPseudo).color), background(element)), background(element)) }));
  const controls = [...document.querySelectorAll(controlSelector)]
    .filter(element => element.checkVisibility() && !element.closest(':disabled, [inert], .disabled'))
    .map(element => {
      const css = getComputedStyle(element);
      const side = ['Top', 'Bottom', 'Left', 'Right'].find(side => parseFloat(css[`border${side}Width`]) > 0);
      const border = rgba(css[`border${side ?? 'Top'}Color`]);
      return { label: element.getAttribute('aria-label') || element.name || element.className,
        border: css[`border${side ?? 'Top'}Color`], ratio: contrast(border, background(element)),
        outsideRatio: contrast(border, background(element.parentElement)) };
    });
  const switches = [...document.querySelectorAll('input[role=switch]:not(:disabled)')].map(input => {
    const track = input.nextElementSibling;
    return { label: input.getAttribute('aria-label'), checked: input.checked,
      thumb: getComputedStyle(track, '::after').backgroundColor, track: getComputedStyle(track).backgroundColor,
      ratio: contrast(rgba(getComputedStyle(track, '::after').backgroundColor), rgba(getComputedStyle(track).backgroundColor)) };
  });
  const focused = document.querySelector('[data-catalog-light]').nextElementSibling;
  const focus = { width: parseFloat(getComputedStyle(focused).outlineWidth),
    ratio: contrast(rgba(getComputedStyle(focused).outlineColor), background(focused.parentElement)) };
  return { text, controls, switches, focus };
}

function spacingSamples() {
  const visible = element => element.checkVisibility() && element.getBoundingClientRect().height > 0;
  const gaps = selector => [...document.querySelectorAll(selector)].flatMap(container => {
    const children = [...container.children].filter(visible);
    return children.slice(1).map((child, index) => child.getBoundingClientRect().top - children[index].getBoundingClientRect().bottom);
  });
  const product = document.querySelector('.catalog-header .brand-product');
  const range = document.createRange();
  range.selectNodeContents(product);
  const brandRects = [...range.getClientRects()].map(rect => ({top: rect.top, width: rect.width}));
  return { stack: gaps('.catalog-stack, .example-stack, .example-form'), sections: gaps('.catalog-sections'),
    brandLines: new Set(brandRects.map(rect => Math.round(rect.top))).size, brandRects,
    headerWidth: document.querySelector('.catalog-header').getBoundingClientRect().width,
    overflow: document.documentElement.scrollWidth - innerWidth };
}

export async function checkPresentation({ navigate, evaluate, until: poll, key, call, artifacts, mode }) {
  const until = expression => poll(`Boolean(${expression})`);
  const measurements = [];
  for (const width of [1440, 390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
    for (const appearance of ['dark', 'light']) {
      for (const path of ['/', '/foundation']) {
        await navigate(path);
        await evaluate(`document.fonts.ready`);
        if (appearance === 'light') await evaluate(`document.querySelector('[data-catalog-light]').click()`);
        await key('Tab');
        await evaluate(`document.querySelector('[data-catalog-light]').focus()`);
        await evaluate(`Promise.all(document.getAnimations().filter(animation=>animation instanceof CSSTransition).map(animation=>animation.finished)).then(()=>true)`);
        const colors = await evaluate(`(${contrastSamples.toString()})()`);
        const spacing = await evaluate(`(${spacingSamples.toString()})()`);
        for (const sample of colors.text) assert.ok(sample.ratio >= 4.5, `${appearance} ${width} ${path}: ${JSON.stringify(sample)}`);
        for (const sample of [...colors.controls, ...colors.switches]) assert.ok(sample.ratio >= 3, `${appearance} ${width}: ${JSON.stringify(sample)}`);
        assert.ok(colors.focus.width >= 2 && colors.focus.ratio >= 3, JSON.stringify(colors.focus));
        assert.equal(spacing.brandLines, 1, `${width}: product label wraps: ${JSON.stringify(spacing)}`);
        assert.ok(spacing.stack.every(gap => gap >= 15.5), JSON.stringify(spacing));
        assert.ok(spacing.sections.every(gap => gap >= 23.5), JSON.stringify(spacing));
        assert.ok(spacing.overflow <= 0, `${width}: document overflow`);
        measurements.push({ width, appearance, path, ...colors, spacing });
        await evaluate(`document.activeElement.blur();document.querySelector('.catalog-content').scrollIntoView({block:'start'})`);
        const shot = await call('Page.captureScreenshot', { format: 'png' });
        await writeFile(join(artifacts, `catalog-${mode}-${appearance}-${width}-${path === '/' ? 'overview' : 'foundation'}.png`), Buffer.from(shot.data, 'base64'));
      }
    }
  }
  // A successful save must not move the controls, and later updates must not replay a dismissed notice.
  await navigate('/foundation');
  await until(`document.querySelector('[data-catalog-form=foundation]').dataset.bmlActionBound==='true'`);
  await evaluate(`(() => {const form=document.querySelector('[data-catalog-form=foundation]');form.elements.displayName.value='Toast example';window.catalogBeforeSave=form.getBoundingClientRect().height;window.catalogBeforeSequence=Number(document.querySelector('[data-catalog-save-toast=foundation]').dataset.sequence);form.requestSubmit();})()`);
  await until(`Number(document.querySelector('[data-catalog-save-toast=foundation]').dataset.sequence)===window.catalogBeforeSequence+1&&document.querySelector('[data-catalog-save-toast=foundation]').matches(':popover-open')&&!document.querySelector('[data-catalog-form=foundation]').hasAttribute('data-bml-action-pending')`);
  assert.equal(await evaluate(`document.querySelector('[data-catalog-form=foundation]').getBoundingClientRect().height`), await evaluate('window.catalogBeforeSave'));
  await evaluate(`document.querySelector('[data-catalog-save-toast=foundation] [data-bml-ref=dismiss]').click()`);
  await evaluate(`window.catalogBeforeChecked=document.querySelector('[data-catalog-updates=foundation]').hasAttribute('checked');document.querySelector('[data-catalog-updates=foundation]').click()`);
  await until(`document.querySelector('[data-catalog-updates=foundation]').hasAttribute('checked')!==window.catalogBeforeChecked`);
  assert.ok(await evaluate(`!document.querySelector('[data-catalog-save-toast=foundation]').matches(':popover-open')&&!document.querySelector('[data-catalog-saves], [data-catalog-success], output')`));
  await writeFile(join(artifacts, `catalog-${mode}-presentation.json`), JSON.stringify(measurements, null, 2));
}
