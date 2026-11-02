// @vitest-environment node
import { describe, expect, it } from "vitest"
// @ts-expect-error - plain .mjs build helper
import { relativeBmlModuleImports } from "../tools/module-urls.mjs"

describe("deployment module URLs", () => {
  it("relativizes static, re-exported, and lazy BML imports without touching literals or external imports", () => {
    const code = `
      import '/_bml/libraries/key/shared.js';
      export { value } from '/_bml/libraries/key/export.js';
      export * from '/_bml/js/chunks/shared.js';
      const lazy = import('/_bml/libraries/key/lazy.js?mode=test#module');
      const template = import(\`/_bml/libraries/key/template.js\`);
      const untouched = '/_bml/libraries/key/string.js';
      // import('/_bml/libraries/key/comment.js')
      const pattern = /import\\('\\/_bml\\/libraries\\/fake.js'\\)/;
      import 'https://example.test/external.js';
      import './chunks/local.js';
    `
    const result = relativeBmlModuleImports(code, "chunks/entry.js")
    for (const file of ["shared", "export", "template"]) expect(result).toContain(`"../libraries/key/${file}.js"`)
    expect(result).toContain('"./shared.js"')
    expect(result).toContain('"../libraries/key/lazy.js?mode=test#module"')
    expect(result).toContain("const untouched = '/_bml/libraries/key/string.js'")
    expect(result).toContain("// import('/_bml/libraries/key/comment.js')")
    expect(result).toContain("import 'https://example.test/external.js'")
    expect(result).toContain("import './chunks/local.js'")
  })

  it("keeps cross-library imports below the common deployment root", () => {
    expect(relativeBmlModuleImports("import '/_bml/libraries/other/module.js'", "libraries/key/modules/entry.js"))
      .toBe('import "../../other/module.js"')
  })
})
