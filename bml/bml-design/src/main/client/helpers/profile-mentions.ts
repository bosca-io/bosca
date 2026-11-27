export interface ProfileMentionRange {
  from: number;
  to: number;
  query: string;
}

/** Match Studio's handle syntax without treating an email address as a mention. */
export function profileMentionRange(value: string, cursor: number): ProfileMentionRange | null {
  const position = Math.max(0, Math.min(cursor, value.length));
  const from = value.slice(0, position).lastIndexOf("@");
  if (from < 0 || (from > 0 && /[A-Za-z0-9_@]/.test(value[from - 1]))) return null;
  const query = value.slice(from + 1, position);
  if (!/^[A-Za-z0-9._-]*$/.test(query)) return null;
  let to = position;
  while (to < value.length && /[A-Za-z0-9._-]/.test(value[to])) to++;
  return { from, to, query };
}

/** Replace the whole active handle, including its suffix when the caret is in the middle. */
export function replaceProfileMention(value: string, range: ProfileMentionRange, handle: string) {
  const suffix = value.slice(range.to);
  const replacement = `@${handle}${/^\s/.test(suffix) ? "" : " "}`;
  return { value: value.slice(0, range.from) + replacement + suffix, cursor: range.from + replacement.length };
}
