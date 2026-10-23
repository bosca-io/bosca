import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { contrastSamples } from './presentation.mjs';

/** Local disclosure, server-loaded children, native destinations and caller-owned outcomes. */
export async function checkTrees({navigate,evaluate,until:poll,call,artifacts,mode}) {
  const until=expression=>poll(`Boolean(${expression})`);
  const truth=expression=>evaluate(`Boolean(${expression})`);
  const node=(part,id='dark')=>`document.querySelector('[data-tree-${part}=${id}]')`;
  const row=(path,id='dark',kind='local')=>`${node(kind,id)}.querySelector('[data-path=${JSON.stringify(path)}]')`;
  const focused=path=>truth(`document.activeElement===${row(path)}.querySelector('.ui-tree-row')`);
  const key=async value=>{for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:value,windowsVirtualKeyCode:{Enter:13,Tab:9,Home:36,End:35,ArrowDown:40,ArrowUp:38,ArrowLeft:37,ArrowRight:39}[value],...(type==='keyDown'&&value==='Enter'?{text:'\r'}:{})});};
  const action=async(name,done)=>{await evaluate(`window.treeAction=${node(name)};window.treeAction.click()`);await until(`!window.treeAction.isConnected&&(${done})`);};
  const listeners=async expression=>{
    const handle=await call('Runtime.evaluate',{expression});
    const result=await call('DOMDebugger.getEventListeners',{objectId:handle.result.objectId});
    await call('Runtime.releaseObject',{objectId:handle.result.objectId});
    return result.listeners.filter(listener=>['bml:update','keydown','click','focusin','focusout','toggle'].includes(listener.type)).length;
  };
  const shot=async name=>{const capture=await call('Page.captureScreenshot',{format:'png'});await writeFile(join(artifacts,`catalog-${mode}-trees-${name}.png`),Buffer.from(capture.data,'base64'));};
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await call('Emulation.setScriptExecutionDisabled',{value:true});await navigate('/trees');
  const fallback=await evaluate(`({links:[...document.querySelectorAll('[data-tree-local=light] a')].length,keyboard:[...document.querySelectorAll('[data-tree-local=light] a')].every(link=>link.tabIndex===0),visible:[...document.querySelectorAll('[data-tree-local=light] a')].every(link=>link.checkVisibility())})`);
  await call('Emulation.setScriptExecutionDisabled',{value:false});await navigate('/trees');
  await until(`${node('refresh')}?.dataset.bmlActionBound==='true'&&${row('docs/guide.md')}.hidden`);
  assert.ok(await truth(`document.querySelector('[data-tree-empty]').parentElement.querySelector('.empty-state p')?.checkVisibility()`), 'Empty tree guidance is visible');
  assert.ok((await call('Accessibility.getFullAXTree')).nodes.some(node => node.role?.value === 'StaticText'
    && node.name?.value === 'The application supplies empty-state content beside the tree.'), 'Empty tree guidance reaches the accessibility tree');
  const disabled=await evaluate(`(()=>{const root=document.querySelector('[data-tree-disabled]');return {disabled:root.querySelector('[data-path=locked] button').disabled,entry:root.querySelector('[tabindex="0"]')?.closest('[role=treeitem]').dataset.path};})()`);
  assert.deepEqual({fallback,disabled},{fallback:{links:11,keyboard:true,visible:true},disabled:{disabled:true,entry:'ready'}});
  const baseline=await listeners('document');
  assert.ok(await truth(`${node('local')}.querySelector('[role=tree]').getAttribute('aria-label')==='Local example files'&&${row('src/components/button.bml')}.getAttribute('aria-level')==='3'&&${row('src/components/button.bml')}.getAttribute('aria-selected')==='true'&&${row('src/components/button.bml')}.querySelector('a').getAttribute('aria-current')==='page'&&${node('local')}.querySelectorAll('[tabindex="0"]').length===1&&!document.querySelector('[data-tree-empty] [role=treeitem]')`));
  await evaluate(`${node('local')}.querySelector('[tabindex="0"]').focus()`);await key('Home');assert.ok(await focused('src'));
  await key('ArrowRight');assert.ok(await focused('src/components'));
  await key('ArrowRight');assert.ok(await focused('src/components/button.bml'));
  await key('ArrowLeft');assert.ok(await focused('src/components'));
  await key('ArrowLeft');assert.ok(await truth(`${row('src/components/button.bml')}.hidden&&${row('src/components')}.getAttribute('aria-expanded')==='false'`));
  await key('ArrowRight');assert.ok(await focused('src/components/button.bml'));
  await key('Home');await key('ArrowUp');assert.ok(await focused('src'));
  await key('d');assert.ok(await focused('docs'));await key('ArrowRight');assert.ok(await focused('docs/guide.md'));
  await key('ArrowLeft');await key('ArrowLeft');assert.ok(await truth(`${row('docs/guide.md')}.hidden`));
  await key('Home');await key('s');assert.ok(await focused('src/components/switch.bml'));
  await key('s');assert.ok(await focused('src/components/select.bml'));
  await key('End');await key('ArrowDown');assert.ok(await focused('long-example.bml'));
  await evaluate(`window.localFiles=${node('local')};window.localFiles.scrollTop=window.localFiles.scrollHeight;window.localFileScroll=window.localFiles.scrollTop;${node('example')}.querySelector('[data-example-states]').open=true`);
  assert.ok(await truth('window.localFileScroll>0'));
  await action('refresh',`${node('toast')}.matches(':popover-open')`);
  const retained=await evaluate(`({root:${node('local')}===window.localFiles,scroll:${node('local')}.scrollTop,previousScroll:window.localFileScroll,focused:document.activeElement===${row('long-example.bml')}.querySelector('a'),localClosed:${row('docs/guide.md')}.hidden,otherClosed:${row('docs/guide.md','light')}.hidden,disclosure:${node('example')}.querySelector('details').open})`);
  assert.ok(retained.root&&retained.scroll===retained.previousScroll&&retained.focused&&retained.localClosed&&retained.otherClosed&&retained.disclosure,JSON.stringify(retained));
  await evaluate(`${node('toast')}.querySelector('[data-bml-ref=dismiss]').click()`);
  await action('reject',`${node('reject')}.checked`);
  await evaluate(`window.remoteDirectory=${node('directory')};window.remoteDirectory.focus()`);await key('ArrowRight');
  await until(`${node('toast')}.dataset.tone==='error'&&${node('toast')}.matches(':popover-open')&&!${row('remote','dark','server')}.hasAttribute('data-bml-action-pending')`);
  assert.ok(await truth(`${node('directory')}===window.remoteDirectory&&!${row('remote/button.bml','dark','server')}&&${row('remote','dark','server')}.getAttribute('aria-expanded')==='false'&&document.activeElement===window.remoteDirectory&&${node('toast')}.querySelector('[role=alert]').textContent.includes('Directory loading rejected')`));
  await action('reject',`!${node('reject')}.checked`);
  await evaluate(`(()=>{const original=window.fetch.bind(window);window.fetch=async(...args)=>{if(String(args[0]).includes('/_bml/action')){window.fetch=original;await new Promise(resolve=>{window.releaseTreeLoad=resolve;});}return original(...args);};window.directorySize=${node('directory')}.getBoundingClientRect().toJSON();${node('directory')}.focus()})()`);await key('ArrowRight');
  await until(`typeof window.releaseTreeLoad==='function'&&${row('remote','dark','server')}.hasAttribute('data-bml-action-pending')`);
  assert.ok(await truth(`(()=>{const button=${node('directory')},box=button.getBoundingClientRect();return button.closest('[role=treeitem]').getAttribute('aria-busy')==='true'&&box.height===window.directorySize.height&&box.width===window.directorySize.width;})()`));
  await evaluate('window.releaseTreeLoad()');
  await until(`${row('remote/button.bml','dark','server')}&&document.activeElement===${row('remote/button.bml','dark','server')}.querySelector('a')`);
  assert.ok(await truth(`${node('directory')}===window.remoteDirectory&&${node('directory')}.getAttribute('aria-label')==='Collapse Examples'&&!${row('remote/button.bml','light','server')}`));
  await key('ArrowLeft');await key('ArrowLeft');await until(`!${row('remote/button.bml','dark','server')}`);
  await evaluate(`(()=>{const original=window.fetch.bind(window);window.fetch=async(...args)=>{if(String(args[0]).includes('/_bml/action')){window.fetch=original;await new Promise(resolve=>{window.releaseOtherTreeLoad=resolve;});}return original(...args);};${node('directory')}.focus()})()`);await key('ArrowRight');
  await until(`typeof window.releaseOtherTreeLoad==='function'`);
  await evaluate(`document.querySelector('[data-catalog-light]').focus();window.releaseOtherTreeLoad()`);
  await until(`${row('remote/button.bml','dark','server')}&&!${row('remote','dark','server')}.hasAttribute('data-bml-action-pending')`);
  assert.ok(await truth(`document.activeElement===document.querySelector('[data-catalog-light]')`),'Loading does not take focus back after the viewer leaves the tree');
  await action('replace',`${row('src/components/input.bml')}.getAttribute('aria-selected')==='true'`);
  assert.ok(await truth(`${node('local')}!==window.localFiles&&!window.localFiles.isConnected&&${node('directory')}===window.remoteDirectory&&${row('remote/button.bml','dark','server')}&&${row('src/components/button.bml','light')}.getAttribute('aria-selected')==='true'`));
  await evaluate(`window.removedTrees=[${node('local')},${node('server')}];window.removedDirectory=${row('src')}`);
  await action('visible',`!${node('local')}`);
  for(let index=0;index<2;index++)assert.equal(await listeners(`window.removedTrees[${index}]`),0);
  await evaluate(`window.removedDirectory.querySelector('button').click()`);
  assert.equal(await evaluate('window.removedDirectory.getAttribute("aria-expanded")'),'true','Removed local trees stop handling disclosure');
  for(let cycle=0;cycle<3;cycle++) {
    await action('visible',`${node('local')}&&${row('docs/guide.md')}.hidden`);
    assert.equal(await listeners('document'),baseline,'Repeated tree mounting restores document listener counts');
    if(cycle<2)await action('visible',`!${node('local')}`);
  }
  await evaluate(`${row('LICENSE')}.querySelector('a').focus()`);await key('Enter');
  await until(`location.pathname==='/components'&&document.readyState==='complete'`);
  const measurements=[];
  for(const width of [1440,390]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});await navigate('/trees');
    await until(`${row('docs/guide.md')}.hidden`);await evaluate(`document.fonts.ready;if(!document.querySelector('[data-catalog-light]').checked)document.querySelector('[data-catalog-light]').click();document.querySelectorAll('[data-example-states]').forEach(details=>details.open=false)`);
    const geometry=await evaluate(`(()=>{const groups=[...document.querySelectorAll('[data-tree-example]')].map(owner=>{const local=owner.querySelector('[data-tree-local-block]'),server=owner.querySelector('[data-tree-server-block]'),tree=owner.querySelector('[data-tree-local]');return {id:owner.dataset.treeExample,gap:server.getBoundingClientRect().top-local.getBoundingClientRect().bottom,treeHeight:tree.getBoundingClientRect().height,rowHeight:tree.querySelector('.ui-tree-row').getBoundingClientRect().height};});const deep=document.querySelector('[data-tree-deep] .tree-label'),box=deep.getBoundingClientRect(),root=deep.closest('.ui-tree').getBoundingClientRect();return {groups,overflow:document.documentElement.scrollWidth-innerWidth,deep:{height:box.height,right:box.right,rootRight:root.right}};})()`);
    assert.ok(geometry.overflow<=0&&geometry.deep.right<=geometry.deep.rootRight+1&&geometry.deep.height>=32,JSON.stringify(geometry));
    for(const group of geometry.groups)assert.ok(group.gap===16&&group.rowHeight===30&&group.treeHeight<=(width===390?220:260),JSON.stringify(group));
    const colors=await evaluate(`(${contrastSamples.toString()})('[data-tree-palette] .tree-label, [data-tree-palette] .ui-tree h3, [data-tree-palette] .example-note')`);
    for(const sample of colors.text)assert.ok(sample.ratio>=4.5,JSON.stringify(sample));
    await evaluate(`document.activeElement.blur();document.querySelector('.catalog-content').scrollIntoView({block:'start'});window.scrollTo(0,0)`);await shot(`page-${width}`);
    for(const id of ['dark','light']) {await evaluate(`${node('local',id)}.scrollIntoView({block:'center'})`);await shot(`${id}-${width}`);}
    for(const index of width===390?[0,1,2,3]:[0,2]) {await evaluate(`document.querySelectorAll('[data-tree-cases] > .ui-card')[${index}].scrollIntoView({block:'start'})`);await shot(`cases-${width}-${index+1}`);}
    measurements.push({width,...geometry,colors:colors.text});
  }
  await writeFile(join(artifacts,`catalog-${mode}-trees.json`),JSON.stringify(measurements,null,2));
  await call('Emulation.setScriptExecutionDisabled',{value:true});await navigate('/trees');
  assert.ok(await truth(`[...${node('local','light')}.querySelectorAll('a')].every(link=>link.tabIndex===0&&link.checkVisibility())&&document.querySelector('[data-tree-disabled] button').disabled&&document.documentElement.scrollWidth<=innerWidth`));
  await shot('native-390');await call('Emulation.setScriptExecutionDisabled',{value:false});
}
