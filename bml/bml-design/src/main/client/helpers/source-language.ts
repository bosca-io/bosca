import { javascript } from "@codemirror/lang-javascript";
import { python } from "@codemirror/lang-python";
import { java } from "@codemirror/lang-java";
import { rust } from "@codemirror/lang-rust";
import { go } from "@codemirror/lang-go";
import { json } from "@codemirror/lang-json";
import { markdown } from "@codemirror/lang-markdown";
import { html } from "@codemirror/lang-html";
import { css } from "@codemirror/lang-css";
import { yaml } from "@codemirror/lang-yaml";
import { sql } from "@codemirror/lang-sql";
import { StreamLanguage } from "@codemirror/language";
import { kotlin } from "@codemirror/legacy-modes/mode/clike";

/** Infer the existing source language from a filename or an explicit language name. */
export function codeLanguage(path: string, language = "") {
  const name = language.toLowerCase();
  const aliases: Record<string, string> = { typescript: "ts", javascript: "js", kotlin: "kt", python: "py", rust: "rs", golang: "go", markdown: "md" };
  const ext = language ? (aliases[name] ?? name) : path.split(".").at(-1)?.toLowerCase();
  if (ext === "ts" || ext === "tsx")
    return javascript({ typescript: true, jsx: ext === "tsx" });
  if (ext === "js" || ext === "jsx" || ext === "mjs")
    return javascript({ jsx: ext === "jsx" });
  if (ext === "kt" || ext === "kts") return StreamLanguage.define(kotlin);
  if (ext === "py") return python();
  if (ext === "java") return java();
  if (ext === "rs") return rust();
  if (ext === "go") return go();
  if (ext === "json") return json();
  if (ext === "md" || ext === "markdown") return markdown();
  if (ext === "html" || ext === "bml" || ext === "vue") return html();
  if (ext === "css" || ext === "scss") return css();
  if (ext === "yaml" || ext === "yml") return yaml();
  if (ext === "sql") return sql();
  return [];
}
