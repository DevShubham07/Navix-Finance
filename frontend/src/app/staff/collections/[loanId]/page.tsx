"use client";

import * as React from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Loader2, RefreshCw, Phone, HandCoins, UserPlus, Banknote, User } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, PermissionGate } from "@/components/staff/live-pipeline";
import { CreditBadge } from "@/components/staff/credit-badge";
import { collectionsApi, customersApi, paiseToINR, rupeesToPaise, type InteractionView, type LoanSummary } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";

const TYPES = ["CALL", "SMS", "EMAIL", "VISIT"];
const OUTCOMES = ["CONNECTED", "NO_ANSWER", "PROMISE_TO_PAY", "PAID", "DISPUTED"];

/**
 * Collections case detail. The route param is the collection-case id (UUID).
 * Shows the real loan + borrower behind the case, the live DPD bucket, and lets
 * staff log interactions, assign an officer, and propose a settlement.
 */
export default function CollectionsCasePage() {
  const { loanId } = useParams<{ loanId: string }>(); // value is the case UUID
  const caseId = loanId;
  const qc = useQueryClient();

  const caseQ = useQuery({ queryKey: ["collections-case", caseId], queryFn: () => collectionsApi.getCase(caseId), enabled: !!caseId });
  const interQ = useQuery({ queryKey: ["collections-interactions", caseId], queryFn: () => collectionsApi.listInteractions(caseId), enabled: !!caseId });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["collections-case", caseId] });
    qc.invalidateQueries({ queryKey: ["collections-interactions", caseId] });
  };

  const c = caseQ.data;

  return (
    <div>
      <Link href="/staff/collections/buckets" className="mb-3 inline-flex items-center gap-1 text-sm text-muted hover:text-navy">
        <ArrowLeft size={15} /> DPD buckets
      </Link>
      <PageHeader title="Collection case" subtitle="Log interactions, assign an officer, and propose a settlement.">
        <button onClick={() => { caseQ.refetch(); interQ.refetch(); }}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink">
          <RefreshCw size={13} /> Refresh
        </button>
      </PageHeader>

      {caseQ.isLoading ? (
        <div className="h-32 animate-pulse rounded border border-line bg-white" />
      ) : caseQ.error || !c ? (
        <p className="text-sm text-error-700">{caseQ.error ? errMessage(caseQ.error) : "Case not found."}</p>
      ) : (
        <div className="grid gap-6 lg:grid-cols-[1fr_minmax(0,360px)]">
          <div className="space-y-6">
            <div className="rounded border border-line bg-white p-5 shadow-sm text-sm">
              <div className="mb-2 font-serif text-base font-semibold text-navy">Case #{c.id.slice(0, 8)}…</div>
              <dl className="grid grid-cols-2 gap-y-1.5">
                <dt className="text-muted">Bucket</dt>
                <dd className="text-right">
                  <span className="rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy">{c.bucket}</span>
                  <span className="ml-2 text-xs text-muted">{c.dpd} DPD</span>
                </dd>
                <dt className="text-muted">Loan</dt><dd className="text-right text-ink">Loan #{c.loanId}{c.loan?.status ? ` · ${c.loan.status}` : ""}</dd>
                <dt className="text-muted">Assigned officer</dt><dd className="text-right text-ink">{c.assignedOfficerName ?? "—"}</dd>
                <dt className="text-muted">Opened</dt><dd className="text-right text-ink">{c.createdAt ? formatDateTime(c.createdAt) : "—"}</dd>
              </dl>
            </div>

            <LoanCard loan={c.loan} />
            <BorrowerCard loan={c.loan} />

            <InteractionsCard
              caseId={caseId}
              interactions={interQ.data ?? []}
              loading={interQ.isLoading}
              onLogged={invalidate}
            />
          </div>

          <div className="space-y-6">
            {/* Assigning a case is collections management — Collection Head (or ADMIN) only.
                A Collection Executive sees the assigned officer read-only. */}
            <PermissionGate
              permission="collections:manage"
              fallback={<AssignedOfficerCard officerName={c.assignedOfficerName} />}
            >
              <AssignCard caseId={caseId} currentOfficerName={c.assignedOfficerName} onAssigned={invalidate} />
            </PermissionGate>
            <SettlementCard caseId={caseId} />
          </div>
        </div>
      )}
    </div>
  );
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <>
      <dt className="text-muted">{label}</dt>
      <dd className="text-right text-ink">{value ?? "—"}</dd>
    </>
  );
}

function LoanCard({ loan }: { loan: LoanSummary | null }) {
  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm text-sm">
      <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-navy"><Banknote size={16} /> Loan</div>
      {!loan ? (
        <p className="text-sm text-muted">Loan could not be resolved.</p>
      ) : (
        <dl className="grid grid-cols-2 gap-y-1.5">
          <Row label="Principal" value={paiseToINR(loan.principalPaise)} />
          <Row label="Net disbursed" value={paiseToINR(loan.netDisbursedPaise)} />
          <Row label="Total repayable" value={paiseToINR(loan.totalRepayablePaise)} />
          <Row label="Outstanding" value={<span className="font-semibold">{paiseToINR(loan.outstandingPaise)}</span>} />
          <Row label="Disbursed on" value={loan.disbursedOn} />
          <Row label="Due date" value={loan.dueDate} />
          <Row label="Status" value={loan.status} />
        </dl>
      )}
    </div>
  );
}

function BorrowerCard({ loan }: { loan: LoanSummary | null }) {
  // Credit headline isn't on the collections LoanSummary — pull it from the customer roll-up
  // (same query key as the customer detail page, so it's deduped/cached).
  const creditQ = useQuery({
    queryKey: ["customer", loan?.applicantId],
    queryFn: () => customersApi.get(loan!.applicantId as number),
    enabled: loan?.applicantId != null,
  });
  const credit = creditQ.data?.profile;
  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm text-sm">
      <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-navy">
        <User size={16} /> Borrower
        {credit && (credit.starRating != null || credit.creditScore != null) && (
          <CreditBadge
            starRating={credit.starRating}
            creditScore={credit.creditScore}
            recommendation={credit.recommendation}
            className="ml-auto"
          />
        )}
      </div>
      {!loan ? (
        <p className="text-sm text-muted">No borrower detail.</p>
      ) : (
        <dl className="grid grid-cols-2 gap-y-1.5">
          <Row label="Name" value={loan.borrowerName} />
          <Row label="PAN" value={loan.panMasked ? <span className="font-mono text-xs">{loan.panMasked}</span> : null} />
          <Row label="Employment" value={loan.employmentStatus} />
          {/* Employer / salary / salary-bank are credit-assessment data — need-to-know for the
              Collection Head (collections:manage), not the executive chasing the payment. */}
          <PermissionGate permission="collections:manage">
            <Row label="Employer" value={loan.employer} />
            <Row label="Monthly salary" value={paiseToINR(loan.monthlySalaryPaise)} />
            <Row label="Salary bank" value={loan.salaryBank} />
          </PermissionGate>
        </dl>
      )}
    </div>
  );
}

function InteractionsCard({
  caseId, interactions, loading, onLogged,
}: { caseId: string; interactions: InteractionView[]; loading: boolean; onLogged: () => void }) {
  const [type, setType] = React.useState("CALL");
  const [outcome, setOutcome] = React.useState("CONNECTED");
  const [ptp, setPtp] = React.useState("");
  const [proof, setProof] = React.useState("");

  const log = useMutation({
    mutationFn: () => collectionsApi.logInteraction(caseId, {
      type, outcome,
      promiseToPayDate: ptp || undefined,
      proofRef: proof.trim() || undefined,
    }),
    onSuccess: () => { setProof(""); setPtp(""); onLogged(); },
  });

  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-navy"><Phone size={16} /> Interactions</div>

      <div className="mb-4 flex flex-wrap items-end gap-3">
        <Select label="Type" value={type} onChange={(e) => setType(e.target.value)} options={TYPES.map((t) => ({ value: t, label: t }))} className="!mb-0" />
        <Select label="Outcome" value={outcome} onChange={(e) => setOutcome(e.target.value)} options={OUTCOMES.map((o) => ({ value: o, label: o }))} className="!mb-0" />
        <Input label="Promise-to-pay" type="date" value={ptp} onChange={(e) => setPtp(e.target.value)} className="!mb-0" />
        {outcome === "PAID" && (
          <Input label="Proof ref" value={proof} onChange={(e) => setProof(e.target.value)} placeholder="UTR / receipt" className="!mb-0" />
        )}
        <button onClick={() => log.mutate()} disabled={log.isPending} className="btn btn-sm btn-navy disabled:opacity-50">
          {log.isPending ? <Loader2 size={13} className="animate-spin" /> : null} Log
        </button>
      </div>
      {log.error && <p className="mb-2 text-sm text-error-700">{errMessage(log.error)}</p>}

      {loading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : interactions.length === 0 ? (
        <p className="text-sm text-muted">No interactions logged yet.</p>
      ) : (
        <ul className="divide-y divide-line text-sm">
          {interactions.map((i) => (
            <li key={i.id} className="flex items-center justify-between gap-2 py-2">
              <span className="text-ink">
                <span className="font-semibold">{i.type}</span> · {i.outcome}
                {i.promiseToPayDate ? <span className="text-muted"> · PTP {i.promiseToPayDate}</span> : null}
                {i.proofRef ? <span className="text-muted"> · proof {i.proofRef}</span> : null}
              </span>
              <span className="flex-shrink-0 text-xs text-muted">{i.loggedAt ? formatDateTime(i.loggedAt) : ""}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Read-only officer view for roles that can't assign (e.g. Collection Executive). */
function AssignedOfficerCard({ officerName }: { officerName: string | null }) {
  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-navy"><UserPlus size={16} /> Assigned officer</div>
      <p className="text-sm text-ink">{officerName ?? "Not yet assigned"}</p>
      <p className="mt-2 text-xs text-muted">The Collection Head assigns cases to officers.</p>
    </div>
  );
}

function AssignCard({ caseId, currentOfficerName, onAssigned }: { caseId: string; currentOfficerName: string | null; onAssigned: () => void }) {
  const officersQ = useQuery({ queryKey: ["collections-officers"], queryFn: collectionsApi.listOfficers });
  const [officerId, setOfficerId] = React.useState("");
  const assign = useMutation({
    mutationFn: () => collectionsApi.assignOfficer(caseId, Number(officerId)),
    onSuccess: onAssigned,
  });
  const officers = officersQ.data ?? [];
  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-navy"><UserPlus size={16} /> Assign officer</div>
      <p className="mb-2 text-xs text-muted">Current: <span className="text-ink">{currentOfficerName ?? "—"}</span></p>
      <Select
        label="Officer (active executives)"
        value={officerId}
        onChange={(e) => setOfficerId(e.target.value)}
        options={[
          { value: "", label: officersQ.isLoading ? "Loading…" : "Select an officer" },
          ...officers.map((o) => ({ value: String(o.id), label: `${o.name} (${o.role})` })),
        ]}
      />
      {officersQ.error && <p className="mb-2 text-xs text-error-700">{errMessage(officersQ.error)}</p>}
      {assign.error && <p className="mb-2 text-sm text-error-700">{errMessage(assign.error)}</p>}
      <button onClick={() => assign.mutate()} disabled={assign.isPending || !officerId} className="btn btn-sm btn-navy btn-block disabled:opacity-50">
        {assign.isPending ? <Loader2 size={13} className="animate-spin" /> : null} Assign
      </button>
    </div>
  );
}

function SettlementCard({ caseId }: { caseId: string }) {
  const qc = useQueryClient();
  const [amount, setAmount] = React.useState("");
  const propose = useMutation({
    mutationFn: () => collectionsApi.proposeSettlement(caseId, rupeesToPaise(Number.parseFloat(amount))),
    onSuccess: () => { setAmount(""); qc.invalidateQueries({ queryKey: ["collections-settlements"] }); },
  });
  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-navy"><HandCoins size={16} /> Propose settlement</div>
      <Input label="Settlement amount (₹)" inputMode="numeric" value={amount}
        onChange={(e) => setAmount(e.target.value.replace(/[^\d.]/g, ""))} placeholder="25000" />
      {propose.error && <p className="mb-2 text-sm text-error-700">{errMessage(propose.error)}</p>}
      {propose.data && <p className="mb-2 text-sm text-success-700">Proposed {paiseToINR(propose.data.settlementAmountPaise)} — pending approval.</p>}
      <button onClick={() => propose.mutate()} disabled={propose.isPending || !amount} className="btn btn-sm btn-gold btn-block disabled:opacity-50">
        {propose.isPending ? <Loader2 size={13} className="animate-spin" /> : null} Propose
      </button>
      <p className="mt-2 text-xs text-muted">A Collection Head approves it on the Settlements page (separation of duties).</p>
    </div>
  );
}
