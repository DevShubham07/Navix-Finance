"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw, Search, ArrowRight, Contact } from "lucide-react";
import { Input } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { PermissionGate, NoAccessNotice, errMessage } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { CreditBadge } from "@/components/staff/credit-badge";
import { CustomerDetailDialog } from "@/components/staff/customer-detail-dialog";
import { customersApi, paiseToINR, statusLabel, type CustomerSummary, type ApplicationStatus } from "@/lib/api/applications";

/**
 * Customers — a borrower-centric roll-up across the loan aggregate. Every staff role can view it
 * (product decision); ADMIN can edit a customer and take lifecycle actions on the detail page.
 * Search matches name or customer id (server-side).
 */
export default function CustomersPage() {
  const [search, setSearch] = React.useState("");
  const [debounced, setDebounced] = React.useState("");
  const [openId, setOpenId] = React.useState<number | null>(null);

  React.useEffect(() => {
    const t = setTimeout(() => setDebounced(search.trim()), 300);
    return () => clearTimeout(t);
  }, [search]);

  const q = useQuery({
    queryKey: ["customers", debounced],
    queryFn: () => customersApi.list(debounced || undefined),
  });

  const rows = q.data ?? [];

  return (
    <div>
      <PageHeader title="Customers" subtitle="Every borrower — search by name or ID, then open to see loans, payments and KYC.">
        <ExportMenu
          title="Customers"
          fileBase="dhanboost-customers"
          columns={[
            { header: "Customer ID", value: (c: CustomerSummary) => c.customerId },
            { header: "Name", value: (c) => c.name ?? "" },
            { header: "PAN", value: (c) => c.pan ?? "" },
            { header: "Mobile", value: (c) => c.mobile ?? "" },
            { header: "Applications", value: (c) => c.applicationCount },
            { header: "Loans", value: (c) => c.loanCount },
            { header: "Latest status", value: (c) => (c.latestStatus ? statusLabel(c.latestStatus as ApplicationStatus) : "") },
            { header: "Outstanding (₹)", value: (c) => (c.totalOutstandingPaise / 100).toFixed(2) },
            { header: "Credit score", value: (c) => c.creditScore ?? "" },
            { header: "Credit rating", value: (c) => (c.starRating != null ? c.starRating.toFixed(1) : "") },
          ]}
          rows={rows}
        />
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <PermissionGate permission="customer:view" fallback={<NoAccessNotice />}>
        <div className="mb-4 flex items-center gap-2">
          <Input
            aria-label="Search customers"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search by name or customer ID"
            leftIcon={<Search size={15} />}
            className="!mb-0"
            inputClassName="w-72"
          />
        </div>

        <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
          {q.isLoading ? (
            <div className="h-40 animate-pulse rounded bg-grey-100" />
          ) : q.error ? (
            <p className="px-5 py-4 text-sm text-error-700">{errMessage(q.error)}</p>
          ) : rows.length === 0 ? (
            <p className="px-5 py-8 text-center text-sm text-muted">
              No customers{debounced ? ` for “${debounced}”` : ""}.
            </p>
          ) : (
            <table className="w-full text-sm">
              <thead className="border-b border-line bg-grey-50 text-left text-xs uppercase tracking-wide text-muted">
                <tr>
                  <th className="px-4 py-2.5">Customer</th>
                  <th className="px-4 py-2.5">Mobile</th>
                  <th className="px-4 py-2.5">Loans</th>
                  <th className="px-4 py-2.5">Outstanding</th>
                  <th className="px-4 py-2.5">CIBIL</th>
                  <th className="px-4 py-2.5">Latest status</th>
                  <th className="px-4 py-2.5 text-right">Open</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {rows.map((c) => (
                  <tr key={c.customerId} className="hover:bg-grey-50">
                    <td className="px-4 py-3">
                      <button onClick={() => setOpenId(c.customerId)} className="flex items-center gap-2 text-left">
                        <span className="grid h-8 w-8 flex-shrink-0 place-items-center rounded-full bg-navy-tint text-navy">
                          <Contact size={15} />
                        </span>
                        <span className="min-w-0">
                          <span className="block font-semibold text-ink hover:underline">{c.name ?? "—"}</span>
                          <span className="block text-xs text-muted">#{c.customerId} · {c.pan ?? "no PAN"}</span>
                        </span>
                      </button>
                    </td>
                    <td className="px-4 py-3 font-mono text-muted">{c.mobile ?? "—"}</td>
                    <td className="px-4 py-3 text-ink">{c.loanCount} <span className="text-xs text-muted">/ {c.applicationCount} apps</span></td>
                    <td className="px-4 py-3 font-semibold text-ink">{paiseToINR(c.totalOutstandingPaise)}</td>
                    <td className="px-4 py-3">
                      {c.starRating != null || c.creditScore != null ? (
                        <CreditBadge starRating={c.starRating} creditScore={c.creditScore} />
                      ) : (
                        <span className="text-xs text-muted">—</span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      {c.latestStatus ? (
                        <span className="rounded-full bg-grey-100 px-2.5 py-0.5 text-xs font-semibold text-ink">
                          {statusLabel(c.latestStatus as ApplicationStatus)}
                        </span>
                      ) : "—"}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <button onClick={() => setOpenId(c.customerId)} className="inline-flex items-center gap-1 text-navy hover:underline">
                        Open <ArrowRight size={14} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </PermissionGate>

      <CustomerDetailDialog customerId={openId} onClose={() => setOpenId(null)} />
    </div>
  );
}
