import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Inspect actual cold-page downloads, including shared chunks and dynamically selected grammars. */
export async function checkResources({ navigate, evaluate, until, call, artifacts, mode }) {
  const engines = {
    chart: { root: '[data-bml-chart]', ready: 'data-chart-ready', js: 'chartjs', css: '[data-bml-chart]' },
    flow: { root: '[data-bml-flow-editor]', ready: 'data-flow-ready', js: 'vue-flow__node', css: '.vue-flow__node' },
    grid: { root: '[data-bml-dashboard-grid]', ready: 'data-grid-ready', js: 'data-grid-ready', css: '[data-bml-dashboard-grid]' },
    document: { root: '[data-bml-document-editor]', ready: 'data-document-ready', js: 'ProseMirror', css: '.tiptap' },
    json: { root: '[data-bml-json-editor]', ready: 'data-json-ready', js: 'jse-main', css: '--jse-' },
    source: { root: '[data-bml-source-view]', ready: 'data-source-ready', js: 'data-source-ready', css: '.source-keyword' },
  };
  const pages = {
    '/foundation': [], '/flows': ['flow'], '/charts': ['chart'], '/dashboards': ['chart', 'grid'],
    '/documents': ['document'], '/json': ['json'], '/source': ['source'],
    '/components/content': ['document', 'json', 'source'],
    '/components/visualization': ['chart', 'flow', 'grid', 'source'],
  };
  const reports = [];
  await call('Network.setCacheDisabled', { cacheDisabled: true });
  try {
    for (const [path, expected] of Object.entries(pages)) {
      await navigate(path);
      for (const name of expected) {
        const { root, ready } = engines[name];
        await until(`document.querySelectorAll(${JSON.stringify(root)}).length>0&&[...document.querySelectorAll(${JSON.stringify(root)})].every(node=>node.hasAttribute(${JSON.stringify(ready)}))`);
      }
      await evaluate('document.fonts.ready');
      // Resource timing entries appear after the response completes, including dynamic imports.
      const resources = await evaluate(`performance.getEntriesByType('resource').map(entry=>({url:entry.name,bytes:entry.decodedBodySize}))`);
      const scripts = [], styles = [];
      for (const resource of resources) {
        const url = new URL(resource.url);
        if (!url.pathname.endsWith('.js') && !url.pathname.endsWith('.css')) continue;
        const response = await fetch(url);
        assert.equal(response.status, 200, resource.url);
        (url.pathname.endsWith('.js') ? scripts : styles).push(await response.text());
      }
      assert.ok(scripts.length > 0 && styles.length > 0, path);
      const js = scripts.join('\n'), css = styles.join('\n');
      for (const [name, engine] of Object.entries(engines)) {
        assert.equal(js.includes(engine.js), expected.includes(name), `${mode} ${path}: ${name} JavaScript`);
        assert.equal(css.includes(engine.css), expected.includes(name), `${mode} ${path}: ${name} CSS`);
      }
      const codeMirror = expected.includes('source') || expected.includes('json');
      assert.equal(js.includes('cm-editor'), codeMirror, `${mode} ${path}: CodeMirror JavaScript`);
      // The source viewer ships CSS; the JSON engine installs CodeMirror base styles from JS.
      assert.equal(css.includes('.cm-editor'), expected.includes('source'), `${mode} ${path}: source editor CSS`);
      reports.push({ path, engines: expected, resources });
    }
    console.log(`Catalog ${mode}: optional engine JS/CSS isolated across ${reports.length} cold pages`);
  } finally {
    await writeFile(join(artifacts, `catalog-${mode}-resources.json`), JSON.stringify(reports, null, 2));
    await call('Network.setCacheDisabled', { cacheDisabled: false });
  }
}
