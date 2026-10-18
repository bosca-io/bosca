/** Values and offset-based asynchronous search supplied by the consuming application. */
export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
  custom?: boolean;
}
export interface SelectSearch {
  query: string;
  offset: number;
  resolve: (options: SelectOption[] | SelectPage) => void;
  reject: (error: unknown) => void;
}
export interface SelectPage {
  options: SelectOption[];
  previousOffset?: number;
  nextOffset?: number;
}

