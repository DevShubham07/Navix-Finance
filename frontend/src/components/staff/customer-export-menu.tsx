"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Download, ChevronDown, FileText, FileSpreadsheet } from "lucide-react";
import { useCan, useStaffMe, ROLE_LABEL } from "@/components/staff/pipeline/hooks";
import { customersApi, staffApi, type CustomerDetail } from "@/lib/api/applications";
import { exportCsvText, exportPdfSections } from "@/lib/export/exporters";
import {
  buildCustomerCsvSections,
  buildCustomerPdf,
  customerCsvText,
  customerExportFileBase,
} from "@/lib/export/customer-export";

/**
 * "Export ▾" on the full customer page — the whole file for ONE borrower (profile, applications,
 * loans, repayments, verifications, references, documents) as a sectioned CSV or a branded PDF.
 *
 * ADMIN-only, like every export in the console (`ExportMenu`'s data-governance rule), gated here on
 * `customer:manage` so it follows the working-role switcher: an ADMIN working as Credit Executive
 * does not see it. The three export-only reads (documents, verifications, references) are fetched
 * when the menu is first opened, not on page load, under the same query keys the Documents /
 * Credit / References tabs use so nothing is fetched twice.
 */
export function CustomerExportMenu({ customerId, detail }: { customerId: number; detail: CustomerDetail | undefined }) {
  const can = useCan();
  const me = useStaffMe().data;
  const [open, setOpen] = React.useState(false);
  const [wanted, setWanted] = React.useState(false);

  const latestAppId = detail?.applications[0]?.id ?? null;
  const documentsQ = useQuery({
    queryKey: ["customer-documents", customerId],
    queryFn: () => customersApi.documents(customerId),
    enabled: wanted,
  });
  const verificationsQ = useQuery({
    queryKey: ["verifications", latestAppId],
    queryFn: () => staffApi.verifications(latestAppId as number),
    enabled: wanted && latestAppId != null,
  });
  const referencesQ = useQuery({
    queryKey: ["staff-references", latestAppId],
    queryFn: () => staffApi.references(latestAppId as number),
    enabled: wanted && latestAppId != null,
    retry: false,
  });

  if (!me || !can("customer:manage")) return null;

  const loading = wanted && (documentsQ.isLoading || verificationsQ.isLoading || referencesQ.isLoading);
  const isDisabled = !detail || loading;
  const actor = { name: me.name, role: ROLE_LABEL[me.role] };
  // A failed extra read leaves its section empty rather than blocking the whole export.
  const extras = () => ({
    documents: documentsQ.data ?? [],
    verifications: verificationsQ.data ?? [],
    references: referencesQ.data ?? [],
  });

  const doCsv = () => {
    if (!detail) return;
    exportCsvText(customerExportFileBase(customerId, new Date()), customerCsvText(buildCustomerCsvSections(detail, extras())));
    setOpen(false);
  };
  const doPdf = () => {
    if (!detail) return;
    exportPdfSections({
      fileBase: customerExportFileBase(customerId, new Date()),
      ...buildCustomerPdf(detail, extras()),
      actor,
      generatedAt: new Date().toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" }),
    });
    setOpen(false);
  };

  // Opening starts the export-only fetches; `wanted` stays true so a re-open does not refetch.
  const toggle = () => {
    if (!open) setWanted(true);
    setOpen((o) => !o);
  };

  return (
    <div className="relative">
      <button
        onClick={toggle}
        disabled={!detail}
        className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs font-semibold text-navy hover:bg-grey-100 disabled:cursor-not-allowed disabled:opacity-50"
        title="Export this customer's full profile"
      >
        <Download size={13} /> {loading ? "Preparing…" : "Export"}{" "}
        <ChevronDown size={12} className={open ? "rotate-180 transition" : "transition"} />
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} aria-hidden />
          <div className="absolute right-0 z-20 mt-1 w-52 overflow-hidden rounded border border-line bg-white shadow-lg">
            <button
              onClick={doCsv}
              disabled={isDisabled}
              className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-ink hover:bg-grey-100 disabled:opacity-50"
            >
              <FileSpreadsheet size={15} className="text-success-600" /> CSV — full profile
            </button>
            <button
              onClick={doPdf}
              disabled={isDisabled}
              className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-ink hover:bg-grey-100 disabled:opacity-50"
            >
              <FileText size={15} className="text-error-600" /> PDF — full profile
            </button>
          </div>
        </>
      )}
    </div>
  );
}
