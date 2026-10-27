import assert from 'node:assert/strict';

/** Evidence for specific axe manual findings; the original incomplete results stay in the report. */
export async function reviewAccessibilityFindings({incomplete,evaluate,call,reviews}) {
  const ax=async expression=>{
    const handle=await call('Runtime.evaluate',{expression});
    assert.ok(handle.result.objectId,`Missing review target: ${expression}`);
    try {
      const tree=await call('Accessibility.getPartialAXTree',{objectId:handle.result.objectId,fetchRelatives:false});
      const node=tree.nodes[0];
      return {ignored:node.ignored,role:node.role?.value,name:node.name?.value,value:node.value?.value,
        properties:Object.fromEntries((node.properties??[]).map(property=>[property.name,property.value]))};
    } finally { await call('Runtime.releaseObject',{objectId:handle.result.objectId}); }
  };
  const contexts=new Map();
  for(const rule of incomplete) {
    if(!['aria-valid-attr-value','aria-toggle-field-name','label-content-name-mismatch'].includes(rule.id))continue;
    for(const finding of rule.nodes) {
      if(rule.id==='aria-valid-attr-value'&&![...finding.any,...finding.all,...finding.none]
        .some(check=>check.data?.messageKey==='controlsWithinPopup'))continue;
      assert.equal(finding.target.length,1,'Review shadow/frame findings explicitly before extending this check');
      const selector=finding.target[0],element=`document.querySelector(${JSON.stringify(selector)})`;
      const dom=await evaluate(`(${inspectFinding.toString()})(${JSON.stringify(selector)})`);
      const row={rule:rule.id,target:finding.target,dom};reviews.push(row);
      assert.equal(dom.count,1,JSON.stringify(row));
      row.ax=await ax(element);
      if(rule.id==='aria-valid-attr-value') {
        assert.ok(dom.owner&&dom.references.length===1,JSON.stringify(row));
        const target=dom.references[0];
        assert.equal(target.count,1,JSON.stringify(row));
        assert.ok(target.owned&&target.intended,JSON.stringify(row));
        row.targetAx=await ax(`document.getElementById(${JSON.stringify(target.id)})`);
        if(target.visible) {
          assert.equal(row.targetAx.ignored,false,JSON.stringify(row));
          assert.equal(row.targetAx.role,target.role,JSON.stringify(row));
          if(!row.ax.ignored)assert.deepEqual(row.ax.properties.controls?.relatedNodes?.map(node=>node.idref),[target.id],JSON.stringify(row));
        } else assert.equal(row.targetAx.ignored,true,JSON.stringify(row));
        if(dom.owner==='select'&&!row.ax.ignored) {
          assert.equal(row.ax.role,'combobox');assert.equal(row.ax.name,dom.label);
          assert.equal(row.ax.value,dom.value);
          assert.equal(row.ax.properties.expanded?.value,target.visible);
        }
      } else if(rule.id==='aria-toggle-field-name') {
        assert.equal(dom.kind,'option',JSON.stringify(row));
        assert.equal(row.ax.ignored,false);assert.equal(row.ax.role,'option');
        assert.ok(dom.text.length>0);assert.equal(row.ax.name,dom.text,JSON.stringify(row));
        assert.equal(row.ax.properties.selected?.value,dom.selected,JSON.stringify(row));
        assert.equal(row.ax.properties.disabled?.value??false,dom.disabled,JSON.stringify(row));
        if(!contexts.has(dom.listId))contexts.set(dom.listId,await ax(`document.getElementById(${JSON.stringify(dom.listId)})`));
        row.listAx=contexts.get(dom.listId);
        assert.equal(row.listAx.ignored,false);assert.equal(row.listAx.role,'listbox');
        assert.equal(row.listAx.name,dom.label,JSON.stringify(row));
      } else {
        assert.equal(dom.kind,'remove',JSON.stringify(row));
        assert.ok(dom.text==='×'&&dom.selectedLabel===dom.tagLabel&&dom.selectedLabel.length>0,JSON.stringify(row));
        assert.equal(row.ax.ignored,false);assert.equal(row.ax.role,'button');
        assert.equal(row.ax.name,`Remove ${dom.selectedLabel}`,JSON.stringify(row));
        assert.equal(row.ax.properties.disabled?.value??false,dom.disabled,JSON.stringify(row));
      }
    }
  }
}

function inspectFinding(selector) {
  const matches=document.querySelectorAll(selector),node=matches[0];
  if(matches.length!==1)return {count:matches.length};
  const select=node.closest('[data-bml-design-select]'),dialog=node.closest('[data-bml-design-dialog]');
  const owner=select??dialog;
  const result={count:1,text:node.textContent.trim()};
  if(node.hasAttribute('aria-controls')) {
    return {...result,owner:select?'select':dialog?'dialog':null,label:select?.dataset.label,
      value:select?.querySelector('[data-bml-ref=selection]').textContent.trim(),
      references:node.getAttribute('aria-controls').split(/\s+/).filter(Boolean).map(id=>{
        const targets=[...document.querySelectorAll('[id]')].filter(target=>target.id===id),target=targets[0];
        return {id,count:targets.length,owned:Boolean(target&&owner?.contains(target)),
          intended:Boolean(target&&(select?target===select.querySelector('[data-bml-ref=options]'):target===dialog?.querySelector(':scope > dialog'))),
          role:select?'listbox':'dialog',visible:target?.checkVisibility()??false};
      })};
  }
  if(node.role==='option')return {...result,kind:'option',label:select?.dataset.label,
    listId:node.closest('[role=listbox]')?.id,selected:node.getAttribute('aria-selected')==='true',disabled:node.getAttribute('aria-disabled')==='true'};
  if(node.matches('.select-tag > button[data-remove]')) {
    const selected=[...select.querySelector('select').selectedOptions].find(option=>option.value===node.dataset.remove);
    return {...result,kind:'remove',selectedLabel:selected?.label,tagLabel:node.previousElementSibling.textContent.trim(),disabled:node.disabled};
  }
  return result;
}
