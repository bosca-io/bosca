import assert from 'node:assert/strict';

/** Result navigation stays compact within an existing action area after actual writes. */
export async function compactResult(link) {
  await link.waitFor();
  const presentation = await link.evaluate(node => ({
    compact: node.matches('a.button-link.size-xs'),
    actions: !!node.closest('.heading-actions, .form-actions, .workops-task-actions'),
    paragraph: !!node.closest('p'),
    size: Number.parseFloat(getComputedStyle(node).fontSize),
    height: node.getBoundingClientRect().height,
  }));
  assert.deepEqual(presentation, { compact: true, actions: true, paragraph: false, size: 12, height: 24 });
}
