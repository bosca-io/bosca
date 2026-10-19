import type { SelectOption, SelectPage, SelectSearch } from "@bosca/bml-design/select";

// Only the consumer's linked binary declarations supply these imports.
export function respond(event: CustomEvent<SelectSearch>) {
  const option: SelectOption = { value: event.detail.query, label: "Result", disabled: false, custom: true };
  const page: SelectPage = { options: [option], previousOffset: 0, nextOffset: event.detail.offset + 2 };
  event.detail.resolve([option]);
  event.detail.resolve(page);
  event.detail.reject(new Error("Search failed"));
  // @ts-expect-error Values and labels are both required.
  event.detail.resolve([{ label: "Missing value" }]);
  // @ts-expect-error Pagination uses numeric offsets.
  event.detail.resolve({ options: [], nextOffset: "cursor" });
  // @ts-expect-error Query text remains a string.
  const query: number = event.detail.query;
  return { page, query };
}
