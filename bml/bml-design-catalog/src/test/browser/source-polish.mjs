import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

export async function checkSourcePolish({ navigate, evaluate, until: poll, call, artifacts, mode }) {
  const until = expression => poll(`Boolean(${expression})`);
  const reports = [];
  for (const width of [1440, 390]) for (const appearance of ['dark', 'light']) {
    await call('Network.clearBrowserCookies');
    await call('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
    await navigate('/source/languages');
    await until(`document.querySelectorAll('[data-source-ready]').length===4`);
    if (appearance === 'light') await evaluate(`document.querySelector('[data-catalog-light]').click()`);
    const languages = await evaluate(`Array.from(document.querySelectorAll('[data-language-example]'),root=>({
      language:root.dataset.languageExample,tokens:[...root.querySelectorAll('.cm-line span')].map(token=>({text:token.textContent,class:token.className,color:getComputedStyle(token).color})),
      wrap:root.querySelector('[data-bml-ref=wrap]').getBoundingClientRect().toJSON(),toolbar:root.querySelector('.source-toolbar').getBoundingClientRect().toJSON()}))`);
    for (const language of languages) {
      assert.ok(language.tokens.some(token => token.class.includes('source-keyword')), JSON.stringify(language));
      assert.ok(new Set(language.tokens.map(token => token.color)).size >= 3, JSON.stringify(language));
      assert.equal(language.wrap.height, 24);
      assert.ok(language.toolbar.right - language.wrap.right >= 9.5 && language.toolbar.right - language.wrap.right <= 10.5);
    }
    const bml = languages.find(language => language.language === 'bml');
    assert.ok(bml.tokens.some(token => token.text === 'val' && token.class.includes('source-keyword')), 'BML server blocks use Kotlin');
    await navigate('/components/content');
    await until(`document.querySelector('[data-export=source-view] .reference-details [data-source-ready] .source-tag')`);
    await evaluate(`document.querySelector('[data-export=source-view] .reference-details').scrollIntoView({block:'center'})`);
    assert.ok(await evaluate(`document.querySelector('[data-export=source-view] .reference-details .source-attribute')!==null`));
    const content = `document.querySelector('[data-export=source-view] .reference-details .cm-content')`;
    await evaluate(`${content}.focus()`);
    await call('Input.insertText', { text: 'This remains read-only' });
    assert.ok(await evaluate(`!${content}.textContent.includes('This remains read-only')`));
    const focus = await evaluate(`(()=>{const content=${content},editor=content.closest('.cm-editor');return {
      background:getComputedStyle(editor.querySelector('.cm-activeLine')).backgroundColor,
      outline:getComputedStyle(editor).outlineColor,accent:getComputedStyle(editor).getPropertyValue('--accent').trim()};})()`);
    assert.equal(focus.background, 'rgba(0, 0, 0, 0)');
    assert.ok(await evaluate('document.documentElement.scrollWidth<=innerWidth'));
    reports.push({ width, appearance, languages, focus });
    const shot = await call('Page.captureScreenshot', { format: 'png' });
    await writeFile(join(artifacts, `catalog-${mode}-source-polish-${appearance}-${width}.png`), Buffer.from(shot.data, 'base64'));
  }
  await writeFile(join(artifacts, `catalog-${mode}-source-polish.json`), JSON.stringify(reports, null, 2));
}
