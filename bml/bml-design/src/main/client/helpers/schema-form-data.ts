import Ajv, { type ValidateFunction } from "ajv";
import addFormats from "ajv-formats";

export type FormDataValue = Record<string, unknown>;

/** Reads a Forms dot-path without conflating absent values with false, zero or null. */
export function formValue(data: FormDataValue, property: string): unknown {
  return property.split(".").reduce<unknown>((value, key) =>
    value && typeof value === "object" ? (value as FormDataValue)[key] : undefined, data);
}

/** Updates only the selected field, retaining values outside the visible UI layout. */
export function setFormValue(data: FormDataValue, property: string, value: unknown): void {
  const keys = property.split(".");
  let parent = data;
  for (const key of keys.slice(0, -1)) {
    const current = Object.hasOwn(parent, key) ? parent[key] : undefined;
    if (!current || typeof current !== "object" || Array.isArray(current)) {
      Object.defineProperty(parent, key, { value: {}, writable: true, enumerable: true, configurable: true });
    }
    parent = parent[key] as FormDataValue;
  }
  const key = keys[keys.length - 1];
  if (value === undefined) delete parent[key];
  else Object.defineProperty(parent, key, { value, writable: true, enumerable: true, configurable: true });
}

/** Uses the same AJV draft and formats as the existing Forms renderer. */
export function formValidator(schema: FormDataValue): ValidateFunction {
  const ajv = new Ajv({ allErrors: true, verbose: true });
  addFormats(ajv);
  const { $schema: _metaSchema, ...definition } = schema;
  return ajv.compile(definition);
}

/** Associates nested required-property errors with the actual missing field. */
export function formErrors(validate: ValidateFunction, data: FormDataValue): Record<string, string> {
  if (validate(data)) return {};
  const errors: Record<string, string> = {};
  for (const error of validate.errors ?? []) {
    const path = error.instancePath.slice(1).split("/").filter(Boolean)
      .map(key => key.replaceAll("~1", "/").replaceAll("~0", "~")).join(".");
    const property = error.keyword === "required"
      ? [path, String(error.params.missingProperty)].filter(Boolean).join(".") : path;
    errors[property] ??= error.message ?? "Invalid value";
  }
  return Object.keys(errors).length ? errors : { "": "Invalid form values" };
}

/** Preserves JSON types from enum schemas and number/boolean controls. */
export function formControlValue(raw: string | boolean | string[], schema: FormDataValue): unknown {
  if (typeof raw === "boolean") return raw;
  if (Array.isArray(raw)) return raw;
  if (raw === "") return undefined;
  const enumeration = Array.isArray(schema.enum) ? schema.enum : [];
  const choice = enumeration.find(value => String(value) === raw);
  if (choice !== undefined) return choice;
  const types = Array.isArray(schema.type) ? schema.type : [schema.type];
  if (types.includes("number") || types.includes("integer")) return Number(raw);
  return raw;
}
