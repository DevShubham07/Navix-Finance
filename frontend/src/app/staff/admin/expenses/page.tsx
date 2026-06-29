"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, Wallet, Trash2, Paperclip, FileText } from "lucide-react";
import { Input } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, NoAccessNotice } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { hasPermission } from "@/lib/auth/rbac";
import { adminApi, storageApi, paiseToINR, rupeesToPaise, type ExpenseResponse } from "@/lib/api/applications";

/** Today as ISO yyyy-mm-dd (local), for the date field default. */
function todayIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/**
 * Admin · company expenses — record operational spend (description, amount, payee, notes) and review
 * it in a sheet-style table with CSV / PDF export. Live `/api/admin/expenses`. ADMIN only.
 */
export default function AdminExpensesPage() {
  const myRole = useStaffMe().data?.role;
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["admin-expenses"], queryFn: adminApi.listExpenses });

  const [description, setDescription] = React.useState("");
  const [amount, setAmount] = React.useState("");
  const [paidTo, setPaidTo] = React.useState("");
  const [notes, setNotes] = React.useState("");
  const [date, setDate] = React.useState<string>(todayIso);
  const [receipt, setReceipt] = React.useState<File | null>(null);
  const receiptInputRef = React.useRef<HTMLInputElement>(null);

  const invalidate = () => qc.invalidateQueries({ queryKey: ["admin-expenses"] });
  const amountRupees = Number(amount);
  const canAdd =
    description.trim() !== "" &&
    paidTo.trim() !== "" &&
    Number.isFinite(amountRupees) &&
    amountRupees > 0 &&
    date !== "";

  const add = useMutation({
    mutationFn: async () => {
      // Upload the optional receipt straight to S3 first, then attach its key to the expense.
      let receiptObjectKey: string | undefined;
      if (receipt) {
        const up = await storageApi.presignUpload({
          category: "EXPENSE_RECEIPT",
          filename: receipt.name,
          contentType: receipt.type || "application/octet-stream",
        });
        await storageApi.putToPresignedUrl(up.url, receipt);
        receiptObjectKey = up.key;
      }
      return adminApi.addExpense({
        description: description.trim(),
        amountPaise: rupeesToPaise(amountRupees),
        paidTo: paidTo.trim(),
        notes: notes.trim() || undefined,
        expenseDate: date || undefined,
        receiptObjectKey,
      });
    },
    onSuccess: () => {
      setDescription("");
      setAmount("");
      setPaidTo("");
      setNotes("");
      setDate(todayIso());
      setReceipt(null);
      if (receiptInputRef.current) receiptInputRef.current.value = "";
      invalidate();
    },
  });
  const remove = useMutation({
    mutationFn: (id: number) => adminApi.removeExpense(id),
    onSuccess: invalidate,
  });

  if (myRole && !hasPermission(myRole, "staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }

  const rows = q.data ?? [];
  const total = rows.reduce((sum, e) => sum + e.amountPaise, 0);

  return (
    <div>
      <PageHeader title="Company expenses" subtitle="Track operational spend — payee, amount and notes. Admin only.">
        <ExportMenu
          title="Company expenses"
          subtitle="Operational expense ledger"
          fileBase="navix-expenses"
          columns={[
            { header: "Date", value: (e: ExpenseResponse) => e.expenseDate },
            { header: "Description", value: (e) => e.description },
            { header: "Paid to", value: (e) => e.paidTo },
            { header: "Amount (₹)", value: (e) => (e.amountPaise / 100).toFixed(2) },
            { header: "Notes", value: (e) => e.notes ?? "" },
            { header: "Receipt", value: (e) => (e.receiptUrl ? "Yes" : "No") },
            { header: "Added by", value: (e) => e.addedBy ?? "" },
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

      <div className="mb-6 rounded border border-line bg-white p-5 shadow-sm">
        <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-navy"><Wallet size={16} /> Add expense</div>
        <div className="flex flex-wrap items-end gap-3">
          <Input label="Description" required value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Office internet — June" className="!mb-0" inputClassName="w-60" />
          <Input label="Amount (₹)" required type="number" min={1} step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="2500" className="!mb-0" inputClassName="w-32" />
          <Input label="Paid to" required value={paidTo} onChange={(e) => setPaidTo(e.target.value)} placeholder="ACT Fibernet" className="!mb-0" inputClassName="w-48" />
          <Input label="Notes (optional)" value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Reference / remarks" className="!mb-0" inputClassName="w-52" />
          <Input label="Date" required type="date" value={date} onChange={(e) => setDate(e.target.value)} className="!mb-0" inputClassName="w-40" />
          <label className="!mb-0">
            <span className="mb-1 flex items-center gap-1.5 text-sm font-semibold text-ink"><Paperclip size={13} /> Receipt (optional)</span>
            <input
              ref={receiptInputRef}
              type="file"
              accept="image/*,application/pdf"
              onChange={(e) => setReceipt(e.target.files?.[0] ?? null)}
              className="block w-64 text-sm text-muted file:mr-3 file:rounded file:border file:border-line file:bg-grey-50 file:px-3 file:py-1.5 file:text-sm file:font-semibold file:text-navy hover:file:bg-grey-100"
            />
          </label>
          <button onClick={() => add.mutate()} disabled={add.isPending || !canAdd} className="btn btn-gold disabled:opacity-50">
            {add.isPending ? <Loader2 size={14} className="animate-spin" /> : null} Add expense
          </button>
        </div>
        {receipt && <p className="mt-2 text-xs text-success-700">Attached: {receipt.name}</p>}
        {add.error && <p className="mt-2 text-sm text-error-700">{errMessage(add.error)}</p>}
      </div>

      {q.isLoading ? (
        <div className="h-32 animate-pulse rounded border border-line bg-white" />
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : (
        <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="border-b border-line bg-grey-50 text-left text-xs uppercase tracking-wide text-muted">
                <tr>
                  <th className="whitespace-nowrap px-4 py-2.5">Date</th>
                  <th className="px-4 py-2.5">Description</th>
                  <th className="px-4 py-2.5">Paid to</th>
                  <th className="whitespace-nowrap px-4 py-2.5 text-right">Amount</th>
                  <th className="px-4 py-2.5">Notes</th>
                  <th className="px-4 py-2.5">Receipt</th>
                  <th className="whitespace-nowrap px-4 py-2.5">Added by</th>
                  <th className="px-4 py-2.5 text-right">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line align-top">
                {rows.map((e: ExpenseResponse) => (
                  <tr key={e.id} className="hover:bg-grey-50">
                    <td className="whitespace-nowrap px-4 py-3 text-muted">{e.expenseDate}</td>
                    <td className="px-4 py-3">
                      <span className="block max-w-[18rem] truncate font-medium text-ink" title={e.description}>{e.description}</span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="block max-w-[12rem] truncate text-ink" title={e.paidTo}>{e.paidTo}</span>
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-right font-semibold text-ink">{paiseToINR(e.amountPaise)}</td>
                    <td className="px-4 py-3">
                      <span className="block max-w-[16rem] truncate text-muted" title={e.notes ?? ""}>{e.notes || "—"}</span>
                    </td>
                    <td className="px-4 py-3">
                      {e.receiptUrl ? (
                        <a
                          href={e.receiptUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="inline-flex items-center gap-1 text-sm font-medium text-navy hover:underline"
                        >
                          <FileText size={13} /> View
                        </a>
                      ) : (
                        <span className="text-muted">—</span>
                      )}
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-muted">{e.addedBy || "—"}</td>
                    <td className="px-4 py-3 text-right">
                      <button onClick={() => remove.mutate(e.id)} disabled={remove.isPending} className="btn btn-sm btn-outline disabled:opacity-50">
                        <Trash2 size={13} /> Remove
                      </button>
                    </td>
                  </tr>
                ))}
                {rows.length === 0 && (
                  <tr><td colSpan={8} className="px-4 py-6 text-center text-muted">No expenses recorded yet.</td></tr>
                )}
              </tbody>
              {rows.length > 0 && (
                <tfoot className="border-t border-line bg-grey-50">
                  <tr>
                    <td className="whitespace-nowrap px-4 py-3 text-xs font-semibold uppercase tracking-wide text-muted" colSpan={3}>
                      Total · {rows.length} {rows.length === 1 ? "expense" : "expenses"}
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-right font-bold text-navy">{paiseToINR(total)}</td>
                    <td colSpan={4} />
                  </tr>
                </tfoot>
              )}
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
