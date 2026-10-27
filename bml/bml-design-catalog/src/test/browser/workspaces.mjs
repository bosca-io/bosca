import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

export async function checkWorkspaces({ navigate, evaluate, until: poll, call, artifacts, mode }) {
  const until = expression => poll(`Boolean(${expression})`);
  const click = async selector => {
    await evaluate(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block:'nearest',inline:'nearest'})`);
    const point=await evaluate(`(() => {const r=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2};})()`);
    for(const type of ['mousePressed','mouseReleased']) await call('Input.dispatchMouseEvent',{type,...point,button:'left',clickCount:1});
  };
  const reports=[];
  for(const width of [1440,390]) for(const light of [false,true]) {
    await call('Network.clearBrowserCookies');
    await call('Emulation.setDeviceMetricsOverride',{width,height:width===390?844:900,deviceScaleFactor:1,mobile:width===390});
    await navigate('/workspaces');
    await until(`!document.querySelector('[data-bml-fullscreen-button]')?.hidden`);
    if(light) await click('[data-catalog-light]');
    assert.equal(await evaluate(`document.querySelector('[data-catalog-light]').checked`),light,'The requested appearance is selected');
    assert.ok(await evaluate(`!document.querySelector('[data-catalog-workspace] [role=tabpanel][aria-label=Source]').checkVisibility()`),'The source panel starts hidden');
    const normal=await evaluate(`(() => {const main=document.querySelector('main'),root=document.querySelector('[data-catalog-workspace]');
      return {rect:root.getBoundingClientRect().toJSON(),bottom:main.getBoundingClientRect().bottom-parseFloat(getComputedStyle(main).paddingBottom),
        outer:main.scrollHeight-main.clientHeight,overflow:document.documentElement.scrollWidth>innerWidth,color:getComputedStyle(root).backgroundColor};})()`);
    assert.ok(Math.abs(normal.rect.bottom-normal.bottom)<=1&&normal.outer<=1&&!normal.overflow,JSON.stringify(normal));
    assert.ok(await evaluate(`(() => {const button=document.querySelector('[data-bml-fullscreen-button]'),rect=button.getBoundingClientRect();
      return button.getAttribute('aria-label')==='Full screen'&&button.dataset.tooltipContent==='Full screen'&&rect.width===30&&rect.height===30&&
        button.querySelector('[data-fullscreen-enter-icon]').checkVisibility()&&!button.querySelector('[data-fullscreen-exit-icon]').checkVisibility();})()`),
      'The catalog uses the shared compact expand icon');
    await click('[data-tab=Source]');
    await until(`document.querySelector('[data-catalog-workspace] .cm-editor')`);
    assert.ok(await evaluate(`!document.querySelector('[data-catalog-workspace] [role=tabpanel][aria-label=Preview]').checkVisibility()`),'Source hides the preview panel');
    await evaluate(`void(window.catalogWorkspaceEditor=document.querySelector('[data-catalog-workspace] .cm-editor'))`);
    await click('[data-bml-fullscreen-button]');
    await until(`document.querySelector('[data-catalog-workspace]').hasAttribute('data-bml-workspace-expanded')&&document.querySelector('[data-bml-fullscreen-button]').getAttribute('aria-busy')!=='true'`);
    const full=await evaluate(`(() => {const root=document.querySelector('[data-catalog-workspace]'),rect=root.getBoundingClientRect();return {
      rect:rect.toJSON(),width:innerWidth,height:innerHeight,color:getComputedStyle(root).backgroundColor,
      bottom:root.querySelector('.cm-editor').getBoundingClientRect().bottom};})()`);
    assert.ok(full.rect.x===0&&full.rect.y===0&&full.rect.width===full.width&&full.rect.height===full.height&&Math.abs(full.bottom-full.rect.bottom)<=1,JSON.stringify(full));
    assert.equal(full.color,normal.color,'Expanded surfaces inherit the catalog appearance');
    assert.ok(await evaluate(`(() => {const button=document.querySelector('[data-bml-fullscreen-button]'),rect=button.getBoundingClientRect();
      return button.getAttribute('aria-label')==='Exit full screen'&&button.dataset.tooltipContent==='Exit full screen'&&rect.width===30&&rect.height===30&&
        !button.querySelector('[data-fullscreen-enter-icon]').checkVisibility()&&button.querySelector('[data-fullscreen-exit-icon]').checkVisibility();})()`),
      'Expansion changes the icon and accessible label without changing dimensions');
    await click('[data-tab=Preview]');
    assert.ok(await evaluate(`!document.querySelector('[data-catalog-workspace] [role=tabpanel][aria-label=Source]').checkVisibility()`),'Preview hides the source panel');
    assert.ok(await evaluate(`document.querySelector('[data-catalog-workspace] [role=tabpanel][aria-label=Preview]').scrollHeight>document.querySelector('[data-catalog-workspace] [role=tabpanel][aria-label=Preview]').clientHeight`));
    await writeFile(join(artifacts,`${mode}-catalog-workspace-${width}-${light?'light':'dark'}.png`),Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));
    await click('[data-bml-fullscreen-button]');
    await until(`!document.querySelector('[data-catalog-workspace]').hasAttribute('data-bml-workspace-expanded')`);
    await click('[data-tab=Source]');
    assert.ok(await evaluate(`document.querySelector('[data-catalog-workspace] .cm-editor')===window.catalogWorkspaceEditor&&![...document.querySelectorAll('.catalog-header')].some(e=>e.inert)`));
    reports.push({width,light,normal,full});
  }
  await writeFile(join(artifacts,`${mode}-catalog-workspaces.json`),JSON.stringify(reports,null,2));
}
