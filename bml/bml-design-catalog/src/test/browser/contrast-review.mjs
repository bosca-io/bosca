import assert from 'node:assert/strict';

/** Measures solid paints without treating an axe incomplete result as an automatic pass. */
export function inspectContrast(selector) {
  const matches = document.querySelectorAll(selector), element = matches[0];
  if (matches.length !== 1) return { count: matches.length };
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = 1;
  const context = canvas.getContext('2d', { willReadFrequently: true });
  const rgba = color => {
    context.clearRect(0, 0, 1, 1); context.fillStyle = color; context.fillRect(0, 0, 1, 1);
    return [...context.getImageData(0, 0, 1, 1).data];
  };
  const over = (front, back) => front.slice(0, 3)
    .map((value, index) => value * front[3] / 255 + back[index] * (1 - front[3] / 255)).concat(255);
  const unsupported = [], layers = [];
  let background = [0, 0, 0, 0], opaque = false;
  for (let node = element; node; node = node.parentElement) {
    const css = getComputedStyle(node);
    for (const [property, expected] of [['opacity', '1'], ['filter', 'none'], ['backdropFilter', 'none'], ['mixBlendMode', 'normal']]) {
      if (css[property] !== expected) unsupported.push({ node: node.tagName, property, value: css[property] });
    }
    if (!opaque) {
      if (css.backgroundImage !== 'none') unsupported.push({ node: node.tagName, property: 'backgroundImage', value: css.backgroundImage });
      const color = rgba(css.backgroundColor); layers.push(css.backgroundColor);
      const alpha = background[3] / 255, nextAlpha = alpha + color[3] / 255 * (1 - alpha);
      background = background.slice(0, 3).map((value, index) => nextAlpha
        ? (value * alpha + color[index] * color[3] / 255 * (1 - alpha)) / nextAlpha : 0).concat(nextAlpha * 255);
      opaque = background[3] === 255;
    }
  }
  if (!opaque) unsupported.push({ property: 'background', value: 'No opaque ancestor' });
  const css = getComputedStyle(element), foreground = over(rgba(css.color), background);
  if (css.webkitTextFillColor && css.webkitTextFillColor !== css.color) unsupported.push({ property: 'webkitTextFillColor', value: css.webkitTextFillColor });
  const luminance = color => color.slice(0, 3).map(value => value / 255)
    .map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4)
    .reduce((sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index], 0);
  const values = [luminance(foreground), luminance(background)].sort((a, b) => a - b);
  // A direct text rectangle must intersect every clipping ancestor and survive hit testing.
  const clip = { left: 0, top: 0, right: innerWidth, bottom: innerHeight };
  for (let node = element; node; node = node.parentElement) {
    const style = getComputedStyle(node), rect = node.getBoundingClientRect();
    if (/auto|scroll|hidden|clip/.test(style.overflowX)) {
      clip.left = Math.max(clip.left, rect.left + node.clientLeft);
      clip.right = Math.min(clip.right, rect.left + node.clientLeft + node.clientWidth);
    }
    if (/auto|scroll|hidden|clip/.test(style.overflowY)) {
      clip.top = Math.max(clip.top, rect.top + node.clientTop);
      clip.bottom = Math.min(clip.bottom, rect.top + node.clientTop + node.clientHeight);
    }
    if (node.matches(':popover-open, :modal')) break;
  }
  const textRects = [...element.childNodes].filter(node => node.nodeType === Node.TEXT_NODE && node.textContent.trim()).flatMap(node => {
    const range = document.createRange(); range.selectNodeContents(node);
    return [...range.getClientRects()].map(rect => ({ left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom }));
  });
  const visibleText = textRects.some(rect => {
    const left = Math.max(rect.left, clip.left), right = Math.min(rect.right, clip.right);
    const top = Math.max(rect.top, clip.top), bottom = Math.min(rect.bottom, clip.bottom);
    if (right <= left || bottom <= top) return false;
    const hit = document.elementFromPoint((left + right) / 2, (top + bottom) / 2);
    return hit === element || element.contains(hit);
  });
  return { count: 1, text: element.textContent.trim(), color: css.color, layers, foreground, background,
    ratio: (values[1] + 0.05) / (values[0] + 0.05), unsupported, visibleText,
    font: { size: css.fontSize, weight: css.fontWeight }, clip, textRects };
}

export function assertContrast(evidence) {
  assert.equal(evidence.count, 1, JSON.stringify(evidence));
  assert.deepEqual(evidence.unsupported, [], `Review unsupported paint explicitly: ${JSON.stringify(evidence)}`);
  assert.ok(evidence.ratio >= 4.5, JSON.stringify(evidence));
}

export async function reviewContrastFindings({ incomplete, evaluate, reviews }) {
  const findings = incomplete.filter(rule => rule.id === 'color-contrast').flatMap(rule => rule.nodes);
  for (const finding of findings) assert.equal(finding.target.length, 1, 'Review shadow/frame contrast explicitly');
  const evidence = await evaluate(`(${JSON.stringify(findings.map(finding => finding.target[0]))}).map(selector => {
    const matches = [...document.querySelectorAll(selector)];
    return matches.map((element, matchIndex) => {
      let resolvedSelector = selector;
      if (matches.length > 1) {
        const parts = [];
        for (let node = element; node; node = node.parentElement) {
          parts.unshift(node.parentElement
            ? node.localName + ':nth-child(' + ([...node.parentElement.children].indexOf(node) + 1) + ')'
            : node.localName);
        }
        resolvedSelector = parts.join(' > ');
      }
      return { matchedCount: matches.length, matchIndex, resolvedSelector,
        dom: (${inspectContrast.toString()})(resolvedSelector) };
    });
  })`);
  findings.forEach((finding, index) => {
    assert.ok(evidence[index].length > 0, `Missing contrast target: ${JSON.stringify(finding.target)}`);
    for (const match of evidence[index]) {
      const row = { rule: 'color-contrast', target: finding.target,
        reasons: [...finding.any, ...finding.all, ...finding.none].map(check => check.data?.messageKey), ...match };
      reviews.push(row); assertContrast(row.dom);
    }
  });
}
