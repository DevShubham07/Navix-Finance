"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, HandCoins, Loader2, ShieldCheck, XCircle } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { errMessage, PermissionGate } from "@/components/staff/live-pipeline";
import {
  collectionsApi,
  paiseToINR,
  rupeesToPaise,
  COLLECTION_PAYMENT_KINDS,
  type CollectionPaymentKind,
  type CollectionPaymentView,
} from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";

/**
 * The collections payment chain (V47; revamp.md decisions 43, 44) on the staff side.
 *
 * <p>Three surfaces, one rule: **the officer who takes a payment never books it.** They record it
 * here; the Accountant validates it against the bank on their own queue; only that validation
 * credits the loan. A settlement takes one extra hop through the Collection Head, because it writes
 * off part of the debt.
 */

const STATUS_LABEL: Record<string, { text: string; cls: string }> = {
  PENDING_HEAD: { text: "Awaiting Collection Head", cls: "bg-gold-tint text-gold-dark" },
  PENDING_ACCOUNTANT: { text: "Awaiting accountant", cls: "bg-navy-tint text-navy" },
  VALIDATED: { text: "Validated", cls: "bg-success-50 text-success-700" },
  REJECTED: { text: "Rejected", cls: "bg-error-50 text-error-700" },
};

export function PaymentStatusPill({ status }: { status: string }) {
  const s = STATUS_LABEL[status] ?? { text: status, cls: "bg-grey-100 text-muted" };
  return <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${s.cls}`}>{s.text}</span>;
}

function kindLabel(kind: CollectionPaymentKind) {
  return COLLECTION_PAYMENT_KINDS.find((k) => k.value === kind)?.label ?? kind;
}

// ---------------------------------------------------------------------------
// Record a payment — Collection Executive / Head, on the case detail page
// ---------------------------------------------------------------------------

export function RecordPaymentCard({ caseId, onRaised }: { caseId: string; onRaised?: () => void }) {
  const qc = useQueryClient();
  const [kind, setKind] = React.useState<CollectionPaymentKind>("PART_PAYMENT");
  const [amount, setAmount] = React.useState("");
  const [paidOn, setPaidOn] = React.useState(() => new Date().toISOString().slice(0, 10));
  const [txnRef, setTxnRef] = React.useState("");
  const [proofRef, setProofRef] = React.useState("");

  const raise = useMutation({
    mutationFn: () =>
      collectionsApi.raisePayment(caseId, {
        kind,
        amountPaise: rupeesToPaise(Number.parseFloat(amount)),
        paidOn: paidOn || undefined,
        txnRef: txnRef.trim() || undefined,
        proofRef: proofRef.trim() || undefined,
      }),
    onSuccess: () => {
      setAmount("");
      setTxnRef("");
      setProofRef("");
      qc.invalidateQueries({ queryKey: ["collection-payments"] });
      onRaised?.();
    },
  });

  const hint = COLLECTION_PAYMENT_KINDS.find((k) => k.value === kind)?.hint;

  return (
    <PermissionGate permission="collections:interact">
      <div className="rounded border border-line bg-white p-5 shadow-sm">
        <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-navy">
          <HandCoins size={16} /> Record a payment
        </div>
        <Select
          label="What did the borrower pay?"
          value={kind}
          onChange={(e) => setKind(e.target.value as CollectionPaymentKind)}
          options={COLLECTION_PAYMENT_KINDS.map((k) => ({ value: k.value, label: k.label }))}
        />
        {hint && <p className="-mt-1 mb-3 text-xs text-muted">{hint}</p>}
        <Input
          label="Amount (₹)"
          inputMode="numeric"
          value={amount}
          onChange={(e) => setAmount(e.target.value.replace(/[^\d.]/g, ""))}
          placeholder="5000"
        />
        <Input label="Paid on" type="date" value={paidOn} onChange={(e) => setPaidOn(e.target.value)} />
        <Input
          label="Transaction id (optional)"
          value={txnRef}
          onChange={(e) => setTxnRef(e.target.value)}
          placeholder="UTR / UPI reference"
        />
        <Input
          label="Proof (optional)"
          value={proofRef}
          onChange={(e) => setProofRef(e.target.value)}
          placeholder="Screenshot reference or a note"
        />
        {raise.error && <p className="mb-2 text-sm text-error-700">{errMessage(raise.error)}</p>}
        {raise.data && (
          <p className="mb-2 text-sm text-success-700">
            Recorded {paiseToINR(raise.data.amountPaise)} —{" "}
            {raise.data.status === "PENDING_HEAD"
              ? "waiting on the Collection Head."
              : "waiting on the accountant."}
          </p>
        )}
        <button
          onClick={() => raise.mutate()}
          disabled={raise.isPending || !amount}
          className="btn btn-sm btn-gold btn-block disabled:opacity-50"
        >
          {raise.isPending ? <Loader2 size={13} className="animate-spin" /> : null} Record payment
        </button>
        <p className="mt-2 text-xs text-muted">
          Nothing is credited to the loan until the accountant validates it against the bank.
        </p>
      </div>
    </PermissionGate>
  );
}

// ---------------------------------------------------------------------------
// A case's payment history
// ---------------------------------------------------------------------------

export function CasePaymentsCard({ caseId }: { caseId: string }) {
  const q = useQuery({
    queryKey: ["collection-payments", "case", caseId],
    queryFn: () => collectionsApi.listCasePayments(caseId),
    enabled: !!caseId,
  });
  const rows = q.data ?? [];

  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm text-sm">
      <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-navy">
        <ShieldCheck size={16} /> Payments collected
      </div>
      {q.isLoading ? (
        <div className="h-16 animate-pulse rounded bg-grey-100" />
      ) : rows.length === 0 ? (
        <p className="text-sm text-muted">No payments recorded on this case yet.</p>
      ) : (
        <ul className="divide-y divide-line">
          {rows.map((p) => (
            <li key={p.id} className="py-2.5">
              <div className="flex items-baseline justify-between gap-3">
                <span className="font-semibold text-navy">{paiseToINR(p.amountPaise)}</span>
                <PaymentStatusPill status={p.status} />
              </div>
              <div className="mt-0.5 text-xs text-muted">
                {kindLabel(p.kind)} · paid {p.paidOn ?? "—"} · recorded by {p.raisedByName ?? `#${p.raisedBy}`}
                {p.txnRef ? ` · ${p.txnRef}` : ""}
              </div>
              {p.remarks && (
                <div className="mt-1 text-xs text-ink">
                  <span className="text-muted">Accountant:</span> {p.remarks}
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// The Collection Head's approval queue (settlement payments only)
// ---------------------------------------------------------------------------

export function CollectionPaymentApprovalQueue() {
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["collection-payments", "PENDING_HEAD"],
    queryFn: () => collectionsApi.listPayments("PENDING_HEAD"),
    refetchInterval: 10000,
  });
  const approve = useMutation({
    mutationFn: (id: string) => collectionsApi.headApprovePayment(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["collection-payments"] }),
  });

  return (
    <QueueShell
      title="Settlement payments to approve"
      info="Payments recorded against a settlement. Approving does both halves of your decision — it approves the settlement itself and releases the payment to the accountant to validate. You cannot approve one you recorded yourself."
      rows={q.data ?? []}
      loading={q.isLoading}
      empty="No settlement payments waiting on you."
      error={q.error ?? approve.error}
      actions={(p) => (
        <button
          onClick={() => approve.mutate(p.id)}
          disabled={approve.isPending}
          className="btn btn-sm btn-navy disabled:opacity-50"
        >
          {approve.isPending ? <Loader2 size={13} className="animate-spin" /> : null} Approve
        </button>
      )}
    />
  );
}

// ---------------------------------------------------------------------------
// The Accountant's validation queue
// ---------------------------------------------------------------------------

export function CollectionPaymentValidationQueue() {
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["collection-payments", "PENDING_ACCOUNTANT"],
    queryFn: () => collectionsApi.listPayments("PENDING_ACCOUNTANT"),
    refetchInterval: 10000,
  });

  return (
    <QueueShell
      title="Collections payments to validate"
      info="Money the collections team took from borrowers. Validating credits it to the loan; rejecting sends it back to the officer and the Collection Head with your remarks."
      rows={q.data ?? []}
      loading={q.isLoading}
      empty="No collections payments awaiting validation."
      error={q.error}
      actions={(p) => (
        <ValidateActions
          payment={p}
          onDone={() => qc.invalidateQueries({ queryKey: ["collection-payments"] })}
        />
      )}
    />
  );
}

function ValidateActions({ payment, onDone }: { payment: CollectionPaymentView; onDone: () => void }) {
  const [remarks, setRemarks] = React.useState("");
  const m = useMutation({
    mutationFn: (accept: boolean) => collectionsApi.validatePayment(payment.id, accept, remarks.trim() || undefined),
    onSuccess: onDone,
  });

  return (
    <div className="w-full max-w-sm">
      <label className="mb-1 block text-xs font-medium text-muted">Remarks</label>
      <textarea
        rows={2}
        value={remarks}
        onChange={(e) => setRemarks(e.target.value)}
        placeholder="Matched on the statement / amount doesn't match…"
        className="mb-2 w-full rounded border border-line px-3 py-2 text-sm"
      />
      {m.error && <p className="mb-2 text-xs text-error-700">{errMessage(m.error)}</p>}
      <div className="flex gap-2">
        <button
          onClick={() => m.mutate(true)}
          disabled={m.isPending}
          className="btn btn-sm btn-navy disabled:opacity-50"
        >
          {m.isPending ? <Loader2 size={13} className="animate-spin" /> : <CheckCircle2 size={13} />} Validate
        </button>
        {/* Remarks are mandatory on a reject — the officer has to be able to act on it. */}
        <button
          onClick={() => m.mutate(false)}
          disabled={m.isPending || !remarks.trim()}
          className="btn btn-sm btn-outline disabled:opacity-50"
          title={remarks.trim() ? undefined : "Say why before rejecting"}
        >
          <XCircle size={13} /> Reject
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------

function QueueShell({
  title,
  info,
  rows,
  loading,
  empty,
  error,
  actions,
}: {
  title: string;
  info: string;
  rows: CollectionPaymentView[];
  loading: boolean;
  empty: string;
  error: unknown;
  actions: (p: CollectionPaymentView) => React.ReactNode;
}) {
  return (
    <section>
      <div className="mb-1 flex items-baseline gap-2">
        <h3 className="font-serif text-lg text-navy">{title}</h3>
        <span className="text-xs text-muted">({rows.length})</span>
      </div>
      <p className="mb-3 text-xs text-muted">{info}</p>
      {error ? <p className="mb-2 text-sm text-error-700">{errMessage(error)}</p> : null}
      {loading ? (
        <div className="h-20 animate-pulse rounded border border-line bg-white" />
      ) : rows.length === 0 ? (
        <p className="rounded border border-line bg-white px-4 py-6 text-center text-sm text-muted">{empty}</p>
      ) : (
        <ul className="space-y-3">
          {rows.map((p) => (
            <li
              key={p.id}
              className="flex flex-col gap-3 rounded border border-line bg-white p-4 shadow-sm sm:flex-row sm:items-start sm:justify-between"
            >
              <div className="text-sm">
                <div className="font-semibold text-navy">
                  {paiseToINR(p.amountPaise)} · {kindLabel(p.kind)}
                </div>
                <div className="mt-0.5 text-xs text-muted">
                  {p.borrowerName ?? "Borrower"} · loan #{p.loanId} · paid {p.paidOn ?? "—"}
                  {p.txnRef ? ` · ${p.txnRef}` : ""}
                </div>
                <div className="mt-0.5 text-xs text-muted">
                  Recorded by {p.raisedByName ?? `#${p.raisedBy}`} on {formatDateTime(p.raisedAt)}
                </div>
                {p.proofRef && <div className="mt-1 text-xs text-ink">Proof: {p.proofRef}</div>}
              </div>
              {actions(p)}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
