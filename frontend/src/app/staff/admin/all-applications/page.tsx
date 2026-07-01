"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw, Search, ArrowRight } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, NoAccessNotice } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import type { ExportColumn } from "@/lib/export/exporters";
import { hasPermission } from "@/lib/auth/rbac";
import { staffApi, paiseToINR, statusLabel, type AdminApplicationView } from "@/lib/api/applications";

type CompletenessFilter = "ALL" | "COMPLETE" | "INCOMPLETE";

/** Paise -> plain rupee string (2 dp) for the export columns; "" when null. */
const rupees = (p: number | null) => (p == null ? "" : (p / 100).toFixed(2));

/** Every field, for the CSV / PDF (the on-screen table shows the headline columns). */
const EXPORT_COLUMNS: ExportColumn<AdminApplicationView>[] = [
  { header: "App ID", value: (a) => a.id },
  { header: "Customer ID", value: (a) => a.customerId },
  { header: "Status", value: (a) => statusLabel(a.status) },
  { header: "Complete", value: (a) => (a.complete ? "yes" : "no") },
  { header: "Steps", value: (a) => `${a.stepsCompleted}/${a.stepsRequired}` },
  { header: "Agreement", value: (a) => (a.agreementAccepted ? "yes" : "no") },
  { header: "Name", value: (a) => a.fullName ?? "" },
  { header: "PAN", value: (a) => a.pan ?? "" },
  { header: "Aadhaar", value: (a) => a.aadhaar ?? "" },
  { header: "Mobile", value: (a) => a.mobile ?? "" },
  { header: "Email", value: (a) => a.email ?? "" },
  { header: "DOB", value: (a) => a.dob ?? "" },
  { header: "Address", value: (a) => a.address ?? "" },
  { header: "Employer", value: (a) => a.employer ?? "" },
  { header: "Employment", value: (a) => a.employmentStatus ?? "" },
  { header: "Monthly salary (₹)", value: (a) => rupees(a.monthlySalaryPaise) },
  { header: "Salary bank", value: (a) => a.salaryBank ?? "" },
  { header: "Amount requested (₹)", value: (a) => rupees(a.amountRequestedPaise) },
  { header: "Eligible limit (₹)", value: (a) => rupees(a.eligibleLimitPaise) },
  { header: "Purpose", value: (a) => a.purpose ?? "" },
  { header: "Salary day", value: (a) => a.salaryCreditDay ?? "" },
  { header: "Loan ID", value: (a) => a.loanId ?? "" },
  { header: "Assigned exec", value: (a) => a.assignedExecutiveId ?? "" },
  { header: "Credit score", value: (a) => a.creditScore ?? "" },
  { header: "Star rating", value: (a) => (a.starRating != null ? a.starRating.toFixed(1) : "") },
  { header: "Recommendation", value: (a) => a.recommendation ?? "" },
  { header: "Risk", value: (a) => a.riskCategory ?? "" },
  { header: "KYC captured", value: (a) => (a.kycCapturedAt ? a.kycCapturedAt.slice(0, 10) : "") },
];

/**
 * Admin · all applications — EVERY application, complete and incomplete (DRAFT / partially filled),
 * with full KYC detail and an onboarding-completeness flag. Search + completeness filter; full CSV /
 * PDF export. Live `/api/applications/all`. ADMIN only.
 */
export default function AdminAllApplicationsPage() {
  const myRole = useStaffMe().data?.role;
  const [search, setSearch] = React.useState("");
  const [filter, setFilter] = React.useState<CompletenessFilter>("ALL");
  const q = useQuery({ queryKey: ["admin-all-applications"], queryFn: staffApi.listAllApplications });

  if (myRole && !hasPermission(myRole, "staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }

  const all = q.data ?? [];
  const needle = search.trim().toLowerCase();
  const rows = all.filter((a) => {
    if (filter === "COMPLETE" && !a.complete) return false;
    if (filter === "INCOMPLETE" && a.complete) return false;
    if (!needle) return true;
    return [a.id, a.customerId, a.fullName, a.pan, a.mobile, a.email, statusLabel(a.status)]
      .filter((v) => v != null)
      .map((v) => String(v).toLowerCase())
      .some((s) => s.includes(needle));
  });

  return (
    <div>
      <PageHeader title="All applications" subtitle="Every application — complete and incomplete — with full KYC detail. Admin only.">
        <ExportMenu
          title="All applications"
          subtitle="Full application register"
          fileBase="navix-all-applications"
          columns={EXPORT_COLUMNS}
          rows={rows}
        />
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <div className="mb-4 flex flex-wrap items-end gap-2">
        <Input
          aria-label="Search applications"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search name, PAN, mobile, ID or status"
          leftIcon={<Search size={15} />}
          className="!mb-0"
          inputClassName="w-80"
        />
        <Select
          aria-label="Completeness"
          value={filter}
          onChange={(e) => setFilter(e.target.value as CompletenessFilter)}
          className="!mb-0"
          options={[
            { value: "ALL", label: "All applications" },
            { value: "COMPLETE", label: "Complete only" },
            { value: "INCOMPLETE", label: "Incomplete only" },
          ]}
        />
        <span className="pb-2 text-xs text-muted">{rows.length} of {all.length}</span>
      </div>

      {q.isLoading ? (
        <div className="h-40 animate-pulse rounded border border-line bg-white" />
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : (
        <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="border-b border-line bg-grey-50 text-left text-xs uppercase tracking-wide text-muted">
                <tr>
                  <th className="whitespace-nowrap px-4 py-2.5">App</th>
                  <th className="px-4 py-2.5">Customer</th>
                  <th className="whitespace-nowrap px-4 py-2.5">PAN</th>
                  <th className="whitespace-nowrap px-4 py-2.5">Mobile</th>
                  <th className="whitespace-nowrap px-4 py-2.5">Status</th>
                  <th className="whitespace-nowrap px-4 py-2.5">Completeness</th>
                  <th className="whitespace-nowrap px-4 py-2.5 text-right">Amount</th>
                  <th className="whitespace-nowrap px-4 py-2.5">Credit</th>
                  <th className="whitespace-nowrap px-4 py-2.5">Risk</th>
                  <th className="px-4 py-2.5 text-right">Open</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line align-top">
                {rows.map((a) => (
                  <tr key={a.id} className="hover:bg-grey-50">
                    <td className="whitespace-nowrap px-4 py-3 font-mono text-muted">#{a.id}</td>
                    <td className="px-4 py-3">
                      <span className="block max-w-[14rem] truncate font-semibold text-ink" title={a.fullName ?? ""}>{a.fullName || "—"}</span>
                      <span className="block text-xs text-muted">#{a.customerId}{a.email ? ` · ${a.email}` : ""}</span>
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 font-mono text-ink">{a.pan || "—"}</td>
                    <td className="whitespace-nowrap px-4 py-3 font-mono text-muted">{a.mobile || "—"}</td>
                    <td className="whitespace-nowrap px-4 py-3">
                      <span className="rounded-full bg-grey-100 px-2.5 py-0.5 text-xs font-semibold text-ink">{statusLabel(a.status)}</span>
                    </td>
                    <td className="whitespace-nowrap px-4 py-3">
                      {a.complete ? (
                        <span className="rounded-full bg-success-50 px-2.5 py-0.5 text-xs font-semibold text-success-700">Complete</span>
                      ) : (
                        <span className="rounded-full bg-warning-50 px-2.5 py-0.5 text-xs font-semibold text-warning-700"
                          title={a.agreementAccepted ? "" : "Agreement not accepted"}>
                          {a.stepsCompleted}/{a.stepsRequired}{a.agreementAccepted ? "" : " · no e-sign"}
                        </span>
                      )}
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-right text-ink">
                      {a.amountRequestedPaise != null ? paiseToINR(a.amountRequestedPaise) : "—"}
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-muted">
                      {a.creditScore != null ? a.creditScore : "—"}
                      {a.starRating != null ? <span className="text-gold-dark"> · {a.starRating.toFixed(1)}★</span> : null}
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-muted">{a.riskCategory || "—"}</td>
                    <td className="whitespace-nowrap px-4 py-3 text-right">
                      <Link href={`/staff/customers/${a.customerId}`} className="inline-flex items-center gap-1 text-navy hover:underline">
                        Open <ArrowRight size={14} />
                      </Link>
                    </td>
                  </tr>
                ))}
                {rows.length === 0 && (
                  <tr><td colSpan={10} className="px-4 py-6 text-center text-muted">No applications match.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
