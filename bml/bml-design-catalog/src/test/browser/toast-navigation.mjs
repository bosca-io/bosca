import assert from 'node:assert/strict';

/** Returning to saved session state must not announce an earlier action as a new result. */
export async function checkToastNavigation({ navigate, evaluate, until: poll, call }) {
  const until = expression => poll(`Boolean(${expression})`);
  const root = `document.querySelector('[data-catalog-save-toast=foundation]')`;
  const ready = () => until(`document.querySelector('[data-catalog-form=foundation]')?.dataset.bmlActionBound==='true'&&${root}.querySelector('[data-bml-ref=dismiss]')?.hasAttribute('title')===false`);
  const save = async () => {
    const sequence = await evaluate(`Number(${root}.dataset.sequence)`);
    await evaluate(`document.querySelector('[data-catalog-name=foundation]').value='Navigation example';document.querySelector('[data-catalog-form=foundation]').requestSubmit()`);
    await until(`Number(${root}.dataset.sequence)===${sequence + 1}&&${root}.matches(':popover-open')&&!document.querySelector('[data-catalog-form=foundation]').hasAttribute('data-bml-action-pending')`);
    return sequence + 1;
  };
  const leaveAndReturn = async (historyBack = false) => {
    await evaluate(`document.querySelector('nav[aria-label="Design library"] a[href="/components"]').click()`);
    await until(`location.pathname==='/components'&&document.readyState==='complete'`);
    if (historyBack) await evaluate(`history.back()`);
    else await evaluate(`document.querySelector('nav[aria-label="Design library"] a[href="/foundation"]').click()`);
    await until(`location.pathname==='/foundation'&&document.readyState==='complete'`);
    await ready();
  };
  await navigate('/foundation'); await ready();
  const sequence = await save();
  await evaluate(`${root}.querySelector('[data-bml-ref=dismiss]').click()`);
  await until(`!${root}.matches(':popover-open')`);
  await leaveAndReturn();
  assert.equal(await evaluate(`${root}.dataset.sequence`), String(sequence));
  assert.equal(await evaluate(`document.querySelector('[data-catalog-name=foundation]').value`), 'Navigation example');
  assert.ok(await evaluate(`!${root}.matches(':popover-open')&&${root}.querySelector('[role=status]').textContent===''`), 'Returning must not replay or announce the dismissed save');
  await evaluate(`window.catalogToastBeforeReload=true`);
  await call('Page.reload');
  await until(`window.catalogToastBeforeReload!==true&&document.readyState==='complete'&&location.pathname==='/foundation'`);
  await ready();
  assert.ok(await evaluate(`!${root}.matches(':popover-open')`), 'Reloading retained server state must not replay it');
  await evaluate(`window.navigationBeforeChecked=document.querySelector('[data-catalog-updates=foundation]').hasAttribute('checked');document.querySelector('[data-catalog-updates=foundation]').click()`);
  await until(`document.querySelector('[data-catalog-updates=foundation]').hasAttribute('checked')!==window.navigationBeforeChecked`);
  assert.ok(await evaluate(`!${root}.matches(':popover-open')`), 'An unrelated action after returning must not replay the save');

  // A new result with the same text still appears, and expiry is as final as explicit dismissal.
  await save();
  await until(`!${root}.matches(':popover-open')`);
  await leaveAndReturn(true);
  assert.ok(await evaluate(`!${root}.matches(':popover-open')`), 'An expired toast must stay closed after navigation');
  await save();
  await leaveAndReturn(true);
  assert.ok(await evaluate(`!${root}.matches(':popover-open')`), 'Browser Back after leaving a visible toast must not restore the old notification');
  await save();
  await evaluate(`${root}.querySelector('[data-bml-ref=dismiss]').click()`);
}
