export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };

export interface ProviderReportRow {
  path: string;
  value: string;
}

export function humanizeProviderKey(key: string): string {
  return key
    .replace(/[_-]+/g, " ")
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/\s+/g, " ")
    .trim()
    .split(" ")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

export function flattenProviderReport(report: JsonValue): ProviderReportRow[] {
  const rows: ProviderReportRow[] = [];

  function visit(value: JsonValue, path: string): void {
    if (value === null) {
      rows.push({ path: path || "Response", value: "null" });
      return;
    }
    if (Array.isArray(value)) {
      if (value.length === 0) {
        rows.push({ path: path || "Response", value: "[]" });
        return;
      }
      value.forEach((item, index) => visit(item, `${path || "Response"} [${index + 1}]`));
      return;
    }
    if (typeof value === "object") {
      const entries = Object.entries(value);
      if (entries.length === 0) {
        rows.push({ path: path || "Response", value: "{}" });
        return;
      }
      entries.forEach(([key, item]) => {
        const label = humanizeProviderKey(key);
        visit(item, path ? `${path} > ${label}` : label);
      });
      return;
    }
    rows.push({
      path: path || "Response",
      value: typeof value === "string" && value.length === 0 ? '""' : String(value),
    });
  }

  visit(report, "");
  return rows;
}
