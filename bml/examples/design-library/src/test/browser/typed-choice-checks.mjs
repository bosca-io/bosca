import assert from 'node:assert/strict';

/** Availability changes retain the application's selected value and accessible active panel. */
export async function checkTypedChoices({ call, evaluate, until, base }) {
  await call('Page.navigate', { url: base + '/selection' });
  await until(`document.querySelector('[data-selection-choices=light]')?.dataset.bmlActionBound==='true'`);
  await evaluate(`document.querySelector('[data-mode=light] [data-value=paths]').click()`);
  await until(`document.querySelector('[data-mode=light]').dataset.value==='paths'`);
  await evaluate(`document.querySelector('[data-views=light] [data-tab=Source]').click()`);
  await until(`document.querySelector('[data-views=light]').dataset.selected==='Source'`);
  await evaluate(`document.querySelector('[data-selection-choices=light]').click()`);
  await until(`document.querySelector('[data-mode=light] [data-value=paths]').disabled && document.querySelector('[data-views=light] [data-tab=Source]').disabled`);
  assert.deepEqual(await evaluate(`(() => {
    const choices=document.querySelector('[data-mode=light]'), views=document.querySelector('[data-views=light]');
    return [new FormData(document.querySelector('[data-selection-form=light]')).get('mode'),
      choices.querySelector('[aria-checked=true]')?.dataset.value, views.querySelector('[aria-selected=true]')?.dataset.tab,
      choices.querySelector('[data-value=paths]').tabIndex, choices.querySelector('[data-value=content]').tabIndex,
      views.querySelector('[data-tab=Source]').tabIndex, views.querySelector('[data-tab=Preview]').tabIndex,
      document.querySelector('[data-view-panel=light][data-view=Source]').hidden];
  })()`), ['paths','paths','Source',-1,0,-1,0,false]);
  await evaluate(`document.querySelector('[data-selection-choices=light]').click()`);
  await until(`!document.querySelector('[data-views=light] [data-tab=Source]').disabled`);
  assert.deepEqual(await evaluate(`[
    document.querySelector('[data-mode=light] [data-value=paths]').tabIndex,
    document.querySelector('[data-views=light] [data-tab=Source]').tabIndex,
    document.querySelector('[data-views=light] [aria-selected=true]').dataset.tab
  ]`), [0,0,'Source']);
}
