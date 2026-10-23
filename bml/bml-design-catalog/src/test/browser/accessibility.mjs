import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { reviewAccessibilityFindings } from './accessibility-review.mjs';
import { reviewContrastFindings } from './contrast-review.mjs';

/** Full-document axe checks; reports keep manual-review findings separate from confirmed violations. */
export async function checkAccessibility({navigate,evaluate,until:poll,call,artifacts,mode,widths=[1440,390],appearances=['dark','light'],paths,reportName=paths?'editor-accessibility':'accessibility'}) {
  const until=expression=>poll(`Boolean(${expression})`);
  const axe=await readFile(new URL('../../../node_modules/axe-core/axe.min.js',import.meta.url),'utf8');
  const routes=paths??['/','/foundation','/themes','/selection','/choices','/views','/trees','/navigation','/feedback','/surfaces','/source','/source/languages','/documents','/flows','/charts','/dashboards','/components/visualization','/json','/comments','/overlays','/components','/components/controls','/components/layout','/components/feedback','/components/overlays','/components/content','/components/aliases'];
  const reports=[];
  const scan=async(path,width,appearance,state)=>{
    await evaluate('document.fonts.ready');
    await evaluate('Promise.all(document.getAnimations().filter(animation=>Number.isFinite(animation.effect.getComputedTiming().iterations)).map(animation=>animation.finished)).then(()=>true)');
    const result=await evaluate(`axe.run(document,{resultTypes:['violations','incomplete','passes','inapplicable']}).then(result=>({version:result.testEngine.version,url:result.url,violations:result.violations,incomplete:result.incomplete,passes:result.passes.map(rule=>rule.id),inapplicable:result.inapplicable.map(rule=>rule.id)}))`);
    const report={path,width,appearance,state,...result,reviews:[]};reports.push(report);
    try {
      await reviewAccessibilityFindings({incomplete:result.incomplete,evaluate,call,reviews:report.reviews});
      await reviewContrastFindings({incomplete:result.incomplete,evaluate,reviews:report.reviews});
    }
    finally { await writeFile(join(artifacts,`catalog-${mode}-${reportName}.json`),JSON.stringify(reports,null,2)); }
  };
  for(const width of widths) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
    for(const appearance of appearances)for(const path of routes) {
      await call('Network.clearBrowserCookies');
      await navigate(path);
      await until(`[...document.querySelectorAll('[data-bml-method]')].every(node=>node.dataset.bmlActionBound==='true')`);
      if(path==='/json'||path==='/components/content')await until(`document.querySelectorAll('[data-json-ready]').length>=${path==='/json'?8:3}`);
      if(path==='/source'||path==='/components/content')await until(`document.querySelectorAll('[data-source-ready]').length>=${path==='/source'?7:1}`);
      if(path==='/source/languages')await until(`document.querySelectorAll('[data-source-ready]').length===4`);
      if(path==='/flows'||path==='/components/visualization')await until(`document.querySelector('[data-flow-ready]')`);
      if(path==='/charts'||path==='/components/visualization')await until(`document.querySelector('[data-chart-ready]')`);
      if(path==='/dashboards'||path==='/components/visualization')await until(`document.querySelector('[data-grid-ready]')`);
      if(path==='/documents')await until(`document.querySelectorAll('[data-document-ready]').length===3`);
      if(path==='/selection'||path==='/choices')await until(`document.querySelector('select.enhanced')`);
      if(appearance==='light')await evaluate(`if(!document.querySelector('[data-catalog-light]').checked)document.querySelector('[data-catalog-light]').click()`);
      await evaluate(axe);
      await scan(path,width,appearance,'initial');
      if(path==='/flows'||path==='/charts'||path==='/components/visualization') {
        await evaluate(`document.querySelectorAll('[data-bml-flow-editor] details,[data-bml-chart] details').forEach(details=>details.open=true)`);
        await scan(path,width,appearance,'expanded graph outline and chart data');
      }
      if(await evaluate(`document.querySelectorAll('[data-example-states]').length>0`)) {
        await evaluate(`document.querySelectorAll('[data-example-states]').forEach(details=>details.open=true)`);
        await scan(path,width,appearance,'example states');
      }
      if(path==='/foundation') {
        await evaluate(`const form=document.querySelector('[data-catalog-form=foundation]');form.elements.displayName.value='x';form.requestSubmit()`);
        await until(`!document.querySelector('[data-catalog-error=foundation]').hidden`);
        await scan(path,width,appearance,'field error');
      }
      if(path==='/selection'||path==='/choices')for(const id of ['dark','light']) {
        const native=path==='/selection'?`[data-picker-native=${id}]`:`[data-local-single=${id}]`;
        const root=`document.querySelector('${native}').closest('[data-bml-design-select]')`;
        await evaluate(`${root}.querySelector('[data-bml-ref=trigger]').scrollIntoView({block:'center'});${root}.querySelector('[data-bml-ref=trigger]').click()`);
        await until(`${root}.querySelector('[data-bml-ref=popup]').matches(':popover-open')`);
        if(path==='/selection') {
          await evaluate(`${root}.querySelector('[data-bml-ref=query]').value='control';${root}.querySelector('[data-bml-ref=query]').dispatchEvent(new Event('input',{bubbles:true}))`);
          await until(`!${root}.querySelector('[data-bml-ref=popup]').hasAttribute('aria-busy')&&${root}.querySelector('[data-bml-ref=options]').textContent.includes('Button states')`);
        }
        await scan(path,width,appearance,`open choice ${id}`);
        await evaluate(`${root}.querySelector('[data-bml-ref=trigger]').click()`);
      }
      if(path==='/navigation')for(const id of ['dark','light']) {
        const root=`document.querySelector('[data-navigation-tabs=${id}]')`;
        await evaluate(`${root}.querySelector('[data-bml-ref=more]').scrollIntoView({block:'center'});${root}.querySelector('[data-bml-ref=more]').click()`);
        await until(`${root}.querySelector('[data-bml-ref=menu]').matches(':popover-open')`);
        await scan(path,width,appearance,`overflow ${id}`);
        await evaluate(`${root}.querySelector('[data-bml-ref=more]').click()`);
      }
      if(path==='/overlays')for(const id of ['dark','light']) {
        const root=`document.querySelector('[data-catalog-edit=${id}]')`;
        await evaluate(`${root}.querySelector('[data-bml-ref=trigger]').scrollIntoView({block:'center'});${root}.querySelector('[data-bml-ref=trigger]').click()`);
        await until(`${root}.querySelector('dialog').matches(':modal')`);
        await scan(path,width,appearance,`modal ${id}`);
        for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:'Escape',windowsVirtualKeyCode:27});
        await until(`!${root}.querySelector('dialog').open`);
      }
      if(path==='/feedback')for(const id of ['dark','light']) {
        const root=`document.querySelector('[data-feedback-tooltip=${id}][data-placement=right]')`;
        await evaluate(`${root}.querySelector('button').scrollIntoView({block:'center'});${root}.querySelector('button').focus()`);
        await until(`${root}.querySelector('.bosca-tooltip')?.matches(':popover-open')`);
        await scan(path,width,appearance,`tooltip ${id}`);
        await evaluate(`document.activeElement.blur()`);
        await until(`!${root}.querySelector('.bosca-tooltip').matches(':popover-open')`);
        await evaluate(`document.querySelector('[data-feedback-error=${id}]').click()`);
        await until(`document.querySelector('[data-feedback-toast=${id}]').matches(':popover-open')`);
        await scan(path,width,appearance,`error toast ${id}`);
        await evaluate(`document.querySelector('[data-feedback-toast=${id}] [data-bml-ref=dismiss]').click()`);
      }
      if(path==='/surfaces') {
        await evaluate(`const original=window.fetch.bind(window);window.fetch=async(...args)=>{if(String(args[0]).includes('/_bml/action')){window.fetch=original;await new Promise(resolve=>window.releaseAuditLoad=resolve);}return original(...args);};document.querySelector('[data-surface-form=dark]').requestSubmit()`);
        await until(`typeof window.releaseAuditLoad==='function'`);
        await scan(path,width,appearance,'pending table');
        await evaluate('window.releaseAuditLoad()');
        await until(`document.querySelector('[data-surface-table=dark]')`);
        await scan(path,width,appearance,'loaded table');
      }
    }
  }
  await navigate('/components/controls');
  const sample=`document.querySelector('#ref-button .reference-details [data-source-ready]')`;
  await until(sample);
  await evaluate(`${sample}.scrollIntoView({block:'center'});${sample}.querySelector('.cm-content').focus()`);
  for(const type of ['keyDown','keyUp'])await call('Input.dispatchKeyEvent',{type,key:'End',windowsVirtualKeyCode:35});
  await until(`${sample}.querySelector('.cm-scroller').scrollLeft>0`);
  assert.equal(await evaluate(`${sample}.querySelector('.cm-content').getAttribute('aria-label')`),'button authoring source: example.bml');
  const violations=reports.flatMap(report=>report.violations.map(rule=>({path:report.path,width:report.width,appearance:report.appearance,state:report.state,id:rule.id,nodes:rule.nodes.map(node=>({target:node.target,summary:node.failureSummary}))})));
  assert.deepEqual(violations,[],JSON.stringify(violations,null,2));
  const reviewed=reports.flatMap(report=>report.reviews);
  if(!paths)for(const id of ['aria-valid-attr-value','aria-toggle-field-name','label-content-name-mismatch'])assert.ok(reviewed.some(review=>review.rule===id),`No ${id} review evidence`);
  const contrast=reviewed.filter(review=>review.rule==='color-contrast');
  assert.ok(contrast.length>0,'No retained contrast evidence');
  console.log(`Catalog ${mode}: ${reports.length} axe ${reports[0].version} scans, zero confirmed violations; ${reviewed.length-contrast.length} popup/name findings verified against DOM and browser accessibility evidence; ${contrast.length} solid foreground/background measurements. Original manual findings remain in accessibility.json`);
}
