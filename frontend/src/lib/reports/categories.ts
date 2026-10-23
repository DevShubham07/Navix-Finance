/**
 * The reports centre's groups, as the sidebar shows them under "Reports". `label` must equal the
 * category the backend's catalog (ReportService) puts each report in — the page filters on it — and
 * the slug is what rides in the URL (`/staff/admin/reports?seg=risk`).
 */
export const REPORT_CATEGORIES = [
  { seg: "overview", label: "Business overview" },
  { seg: "customers", label: "Customers" },
  { seg: "disbursement", label: "Disbursement" },
  { seg: "collections", label: "Repayments & collections" },
  { seg: "risk", label: "Risk & portfolio quality" },
  { seg: "credit", label: "Applications & credit" },
  { seg: "staff", label: "Staff & productivity" },
  { seg: "finance", label: "Finance & tax" },
  { seg: "compliance", label: "Compliance & audit" },
  { seg: "growth", label: "Growth & partners" },
] as const;

export type ReportCategorySeg = (typeof REPORT_CATEGORIES)[number]["seg"];

export function reportCategoryLabel(seg: string | null | undefined): string | null {
  return REPORT_CATEGORIES.find((c) => c.seg === seg)?.label ?? null;
}
