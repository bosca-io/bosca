import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { contrastSamples } from './presentation.mjs';

/** Search text stays readable over both match backgrounds, including nested syntax tokens. */
export async function checkSourceSearch({navigate,evaluate,until,call,key,artifacts,mode}) {
  const reports=[];
  try {
    for(const width of [1440,390])for(const appearance of ['dark','light'])for(const id of ['dark','light']) {
      await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
      await call('Network.clearBrowserCookies');await navigate('/source');
      await until(`document.querySelectorAll('[data-source-ready]').length===8`);
      if(appearance==='light')await evaluate(`document.querySelector('[data-catalog-light]').click()`);
      await evaluate('document.fonts.ready');
      const root=`document.querySelector('[data-catalog-source=${id}]')`;
      const snapshot=()=>evaluate(`(()=>{const root=${root},box=root.getBoundingClientRect();return {
        width:box.width,height:box.height,source:root.querySelector('textarea').value,
        keyword:getComputedStyle(root.querySelector('.source-keyword')).color,
        sibling:getComputedStyle(document.querySelector('[data-catalog-source=${id==='dark'?'light':'dark'}] .source-keyword')).color};})()`);
      const before=await snapshot();
      await evaluate(`${root}.scrollIntoView({block:'center'});${root}.querySelector('.cm-content').focus()`);
      for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:'f',windowsVirtualKeyCode:70,
        modifiers:await evaluate('/Mac|iPhone|iPad/.test(navigator.platform)?4:2')});
      await until(`${root}.querySelector('input[name=search]')===document.activeElement`);
      for(const character of 'Component')for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{
        type,key:character,windowsVirtualKeyCode:character.toUpperCase().charCodeAt(0),...(type==='keyDown'?{text:character}:{})});
      await until(`${root}.querySelectorAll('.cm-searchMatch').length>1`);
      await key('Enter');
      await until(`Boolean(${root}.querySelector('.cm-searchMatch-selected'))`);
      const selector=`[data-catalog-source=${id}] .cm-searchMatch, [data-catalog-source=${id}] .cm-searchMatch span`;
      const sample=async()=>({colors:(await evaluate(`(${contrastSamples.toString()})(${JSON.stringify(selector)})`)).text,
        tokens:await evaluate(`Array.from(${root}.querySelectorAll('.cm-searchMatch span')).map(el=>({text:el.textContent,class:el.className,color:getComputedStyle(el).color}))`)});
      const normal=await sample();reports.push({width,appearance,id,normal});
      await writeFile(join(artifacts,`catalog-${mode}-source-search.json`),JSON.stringify(reports,null,2));
      assert.ok(normal.tokens.some(token=>token.class.includes('source-comment'))&&normal.tokens.some(token=>token.class.includes('source-string')));
      for(const text of normal.colors)assert.ok(text.ratio>=4.5,JSON.stringify({width,appearance,id,...text}));
      const normalShot=await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(artifacts,`catalog-${mode}-source-search-default-${appearance}-${id}-${width}.png`),Buffer.from(normalShot.data,'base64'));
      await evaluate(`window.searchStyle=document.createElement('style');window.searchStyle.textContent='[data-search-colors] {--source-search-fg:#ffffff;--source-search-match:#281250;--source-selection:#481475;}';document.head.append(window.searchStyle);${root}.setAttribute('data-search-colors','')`);
      const overridden=await sample();reports.at(-1).overridden=overridden;
      assert.ok(overridden.tokens.length>1&&overridden.tokens.every(token=>token.color==='rgb(255, 255, 255)'));
      for(const text of overridden.colors)assert.ok(text.ratio>=4.5,JSON.stringify(text));
      assert.deepEqual(await snapshot(),before,'Search styling preserves source, dimensions and outside/sibling syntax');
      const shot=await call('Page.captureScreenshot',{format:'png'});
      await writeFile(join(artifacts,`catalog-${mode}-source-search-${appearance}-${id}-${width}.png`),Buffer.from(shot.data,'base64'));
      await evaluate(`${root}.removeAttribute('data-search-colors');window.searchStyle.remove()`);
      await key('Escape');await until(`!${root}.querySelector('.cm-search')&&!${root}.querySelector('.cm-searchMatch')`);
      assert.deepEqual(await snapshot(),before,'Closing search restores the ordinary syntax colors');
      assert.ok(await evaluate('document.documentElement.scrollWidth<=innerWidth'));
    }
  } finally {
    await writeFile(join(artifacts,`catalog-${mode}-source-search.json`),JSON.stringify(reports,null,2));
  }
}
