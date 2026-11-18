declare module "@bosca/bml-design/select" {
  /** A native select value and its displayed label. */
  export interface SelectOption {
    value: string;
    label: string;
    disabled?: boolean;
    custom?: boolean;
  }

  /** The detail of a selectsearch event; the application supplies search transport. */
  export interface SelectSearch {
    query: string;
    offset: number;
    resolve: (options: SelectOption[] | SelectPage) => void;
    reject: (error: unknown) => void;
  }

  /** An offset-based result page, with absent offsets disabling that navigation direction. */
  export interface SelectPage {
    options: SelectOption[];
    previousOffset?: number;
    nextOffset?: number;
  }
}
