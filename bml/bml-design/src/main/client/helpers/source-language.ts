import { StreamLanguage } from "@codemirror/language";
import type { Extension } from "@codemirror/state";

const kotlinLanguage = async () => StreamLanguage.define((await import("@codemirror/legacy-modes/mode/clike")).kotlin);

/** BML permits self-closing components and Kotlin/TypeScript in explicitly scoped script blocks. */
async function bml() {
  const [{ html }, { typescriptLanguage }, kotlin] = await Promise.all([
    import("@codemirror/lang-html"), import("@codemirror/lang-javascript"), kotlinLanguage(),
  ]);
  return html({
    selfClosingTags: true,
    nestedLanguages: [
      { tag: "script", attrs: attributes => "server" in attributes, parser: kotlin.parser },
      { tag: "script", attrs: attributes => "client" in attributes, parser: typescriptLanguage.parser },
    ],
  });
}

/** Load only the requested grammar; ESM caches it across viewers and later language changes. */
export async function codeLanguage(path: string, language = ""): Promise<Extension> {
  const name = language.toLowerCase();
  const aliases: Record<string, string> = { typescript: "ts", javascript: "js", kotlin: "kt", python: "py", rust: "rs", golang: "go", markdown: "md" };
  const ext = language ? (aliases[name] ?? name) : path.split(".").at(-1)?.toLowerCase();
  if (ext === "ts" || ext === "tsx")
    return (await import("@codemirror/lang-javascript")).javascript({ typescript: true, jsx: ext === "tsx" });
  if (ext === "js" || ext === "jsx" || ext === "mjs")
    return (await import("@codemirror/lang-javascript")).javascript({ jsx: ext === "jsx" });
  if (ext === "kt" || ext === "kts") return kotlinLanguage();
  if (ext === "py") return (await import("@codemirror/lang-python")).python();
  if (ext === "java") return (await import("@codemirror/lang-java")).java();
  if (ext === "rs") return (await import("@codemirror/lang-rust")).rust();
  if (ext === "go") return (await import("@codemirror/lang-go")).go();
  if (ext === "json") return (await import("@codemirror/lang-json")).json();
  if (ext === "md" || ext === "markdown") return (await import("@codemirror/lang-markdown")).markdown();
  if (ext === "bml") return bml();
  if (ext === "html" || ext === "vue") return (await import("@codemirror/lang-html")).html();
  if (ext === "css" || ext === "scss") return (await import("@codemirror/lang-css")).css();
  if (ext === "yaml" || ext === "yml") return (await import("@codemirror/lang-yaml")).yaml();
  if (ext === "sql") return (await import("@codemirror/lang-sql")).sql();
  return [];
}
