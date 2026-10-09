"use client";

/**
 * Collections tab: the loan's collection case — DPD, officer, promise-to-pay, interactions,
 * assignment, settlement proposal and payments. Ported from the retired case page, except that
 * a case is only opened by an explicit click (viewing must not create rows).
 */

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { HandCoins, Loader2, Phone, UserPlus } from "lucide-react";
import { Badge, EmptyState, ErrorState, Input, Select, Skeleton, toast } from "@/components/ui";
import { Field, FieldGrid } from "@/components/ui/field-grid";
import { CallLogRow, Section } from "@/components/staff/detail-parts";
import { errMessage } from "@/components/staff/live-pipeline";
import { CasePaymentsCard, RecordPaymentCard } from "@/components/staff/collection-payments";
import { LoanSelector, todayISO, useSelectedLoanId } from "@/components/staff/customer-360/loan-card";
import type { TabCtx } from "@/components/staff/customer-360/types";
import { bucketOf, dpdDays, loanCycle, ordinal } from "@/lib/calc/loan-kpis";
import { COLLECTION_BUCKETS } from "@/lib/collection-buckets";
import { parsePositiveRupees, sanitizeRupeeInput } from "@/lib/collections/rupee-amount-input";
import { useStaffSession } from "@/lib/auth/staff-session";
import { can as rbacCan, type Permission } from "@/lib/auth/rbac";
import { istCalendarToday } from "@/lib/customers/customer-360";
import { formatDate, formatDateTime } from "@/lib/utils";
import {
  collectionsApi,
  customersApi,
  isCaseNotAssigned,
  paiseToINR,
  rupeesToPaise,
  type DpdBucket,
  type InteractionView,
} from "@/lib/api/applications";

const TYPES = ["CALL", "SMS", "EMAIL", "VISIT"];
const OUTCOMES = ["CONNECTED", "NO_ANSWER", "PROMISE_TO_PAY", "PAID", "DISPUTED"];

const bucketLabel = (b: DpdBucket) => COLLECTION_BUCKETS.find((x) => x.bucket === b)?.label ?? b;
const bucketVariant = (b: DpdBucket) =>
  b === "UPCOMING" ? "info" : b === "T0_T7" || b === "T8_T30" ? "warning" : "error";

/** The case for a loan plus its interactions; same query keys are shared with the Repayment tab. */
export function useCollectionsCase(loanId: number | null) {
  const caseQ = useQuery({
    queryKey: ["collections-case-by-loan", loanId],
    queryFn: () => collectionsApi.caseByLoan(loanId as number),
    enabled: loanId != null,
    retry: false,
  });
  const c = caseQ.data ?? null;
  const interQ = useQuery({
    queryKey: ["collections-interactions", c?.id],
    queryFn: () => collectionsApi.listInteractions(c!.id),
    enabled: !!c,
    retry: false,
  });
  const sorted = [...(interQ.data ?? [])].sort((a, b) => (b.loggedAt ?? "").localeCompare(a.loggedAt ?? ""));
  return {
    caseQ,
    interQ,
    case: c,
    notAssigned: isCaseNotAssigned(caseQ.error),
    interactions: sorted,
    lastInteractionAt: sorted[0]?.loggedAt ?? null,
    /** The newest promise-to-pay date anyone logged. */
    ptp: sorted.find((i) => i.promiseToPayDate)?.promiseToPayDate ?? null,
  };
}

export function CollectionsTab(ctx: TabCtx) {
  const [loanId, setLoanId] = useSelectedLoanId(ctx);
  const loan = ctx.detail.loans.find((l) => l.id === loanId) ?? null;
  const qc = useQueryClient();
  const sess = useStaffSession().session;
  const can = (p: Permission) => sess?.role != null && rbacCan(sess.realRole, sess.role, p);
  const canManage = can("collections:manage");
  const canInteract = can("collections:interact");
  const col = useCollectionsCase(loanId);
  const c = col.case;

  if (loan == null) return <EmptyState title="No loan on this application yet — nothing to collect." />;

  const changed = () => {
    qc.invalidateQueries({ queryKey: ["collections-case-by-loan"] });
    qc.invalidateQueries({ queryKey: ["collections-interactions"] });
    qc.invalidateQueries({ queryKey: ["collections-worklist"] });
    qc.invalidateQueries({ queryKey: ["customer", ctx.customerId] });
    qc.invalidateQueries({ queryKey: ["staff-repayments"] });
    ctx.onChanged();
  };

  // The server case dpd is measured to today even for a closed loan; a closed loan stops at closedOn.
  const dpd = loan.closedOn ? dpdDays(loan, istCalendarToday()) : (c?.dpd ?? dpdDays(loan, istCalendarToday()));
  const bucket = (loan.closedOn ? null : c?.bucket) ?? (dpd != null ? bucketOf(dpd) : null);
  const cycle = loanCycle(ctx.detail.loans, loan.id);
  const paidUp = loan.status === "CLOSED" || loan.status === "REPAID";
  const ptpOverdue = col.ptp != null && !paidUp && col.ptp < todayISO();

  return (
    <div className="space-y-4">
      <Section
        title="Collections"
        tone={paidUp ? "success" : dpd != null && dpd >= 30 ? "error" : dpd != null && dpd > 0 ? "warning" : "neutral"}
      >
        <div className="space-y-3">
          <LoanSelector loans={ctx.detail.loans} value={loan.id} onChange={setLoanId} />
          <FieldGrid cols={3}>
            <Field label="DPD" keyLabel tone={dpd != null && dpd > 0 ? "error" : "ink"}>
              {dpd != null ? (
                <span className="inline-flex items-center gap-2">
                  {dpd} {dpd === 1 ? "day" : "days"}
                  {bucket && (
                    <Badge variant={bucketVariant(bucket)} size="sm">
                      {bucketLabel(bucket)}
                    </Badge>
                  )}
                </span>
              ) : null}
            </Field>
            <Field label="Assigned officer">{c ? (c.assignedOfficerName ?? "Not yet assigned") : null}</Field>
            <Field label="Case status">{c || col.notAssigned ? "Open" : col.caseQ.isLoading || col.caseQ.error ? null : "No case"}</Field>
            <Field label="Promise to pay" keyLabel tone={ptpOverdue ? "error" : "ink"} caption={ptpOverdue ? "overdue" : undefined}>
              {col.ptp ? formatDate(col.ptp) : null}
            </Field>
            <Field label="Last interaction">{col.lastInteractionAt ? formatDateTime(col.lastInteractionAt) : null}</Field>
            <Field label="Loan cycle">{cycle != null ? `${ordinal(cycle)} advance` : null}</Field>
          </FieldGrid>
        </div>
      </Section>

      {col.caseQ.isLoading ? (
        <Skeleton variant="row" />
      ) : col.notAssigned ? (
        <div className="rounded border border-line bg-grey-50 p-4 text-sm text-muted">
          This case is assigned to another officer.
        </div>
      ) : col.caseQ.error ? (
        <ErrorState error={col.caseQ.error} onRetry={() => void col.caseQ.refetch()} />
      ) : !c ? (
        <NoCase loanId={loan.id} canOpen={canManage} onOpened={changed} />
      ) : (
        <>
          <InteractionsSection
            caseId={c.id}
            canLog={canInteract}
            interactions={col.interactions}
            loading={col.interQ.isLoading}
            error={col.interQ.error}
            onRetry={() => void col.interQ.refetch()}
            onLogged={changed}
          />
          {canManage && <AssignSection caseId={c.id} currentOfficerName={c.assignedOfficerName} onAssigned={changed} />}
          {canInteract && <SettlementSection caseId={c.id} />}
          <Section title="Payments">
            <div className="space-y-3">
              <CasePaymentsCard caseId={c.id} />
              {canInteract && <RecordPaymentCard caseId={c.id} onRaised={changed} />}
              <p className="text-[8.8px] text-muted">
                Verified repayments and the full ledger are on the{" "}
                <button type="button" className="font-semibold text-navy underline" onClick={() => ctx.onTabChange?.("repayment")}>
                  Repayment tab
                </button>
                .
              </p>
            </div>
          </Section>
        </>
      )}

      <CallsSection customerId={ctx.customerId} loanId={loan.id} />
    </div>
  );
}

function NoCase({ loanId, canOpen, onOpened }: { loanId: number; canOpen: boolean; onOpened: () => void }) {
  const open = useMutation({
    mutationFn: () => collectionsApi.openCase(loanId),
    onSuccess: () => {
      onOpened();
      toast.success("Collection case opened");
    },
  });
  return (
    <div>
      <EmptyState
        title="No collection case is open on this loan."
        hint={canOpen ? undefined : "The Collection Head opens and assigns cases."}
        action={
          canOpen ? (
            <button type="button" className="btn btn-sm btn-navy" disabled={open.isPending} onClick={() => open.mutate()}>
              {open.isPending && <Loader2 size={13} className="animate-spin" />} Open collection case
            </button>
          ) : undefined
        }
      />
      {open.error && <p className="mt-2 text-center text-xs text-error-700">{errMessage(open.error)}</p>}
    </div>
  );
}

function InteractionsSection({
  caseId, canLog, interactions, loading, error, onRetry, onLogged,
}: {
  caseId: string;
  canLog: boolean;
  interactions: InteractionView[];
  loading: boolean;
  error: unknown;
  onRetry: () => void;
  onLogged: () => void;
}) {
  const [type, setType] = React.useState("CALL");
  const [outcome, setOutcome] = React.useState("CONNECTED");
  const [ptp, setPtp] = React.useState("");
  const [proof, setProof] = React.useState("");
  const isPromise = outcome === "PROMISE_TO_PAY";
  const isPaid = outcome === "PAID";

  const log = useMutation({
    // Only the fields on screen for this outcome are sent.
    mutationFn: () =>
      collectionsApi.logInteraction(caseId, {
        type,
        outcome,
        promiseToPayDate: isPromise && ptp ? ptp : undefined,
        proofRef: isPaid ? proof.trim() || undefined : undefined,
      }),
    onSuccess: () => {
      setProof("");
      setPtp("");
      onLogged();
      toast.success("Interaction logged");
    },
  });

  return (
    <Section title="Interactions" icon={Phone} pill={<Badge variant="info">{interactions.length}</Badge>}>
      {canLog && (
        <div className="mb-3">
          <div className="flex flex-wrap items-end gap-3">
            <Select label="Type" value={type} onChange={(e) => setType(e.target.value)} options={TYPES.map((t) => ({ value: t, label: t }))} className="!mb-0" />
            <Select label="Outcome" value={outcome} onChange={(e) => setOutcome(e.target.value)} options={OUTCOMES.map((o) => ({ value: o, label: o }))} className="!mb-0" />
            {isPromise && <Input label="Promise-to-pay" type="date" value={ptp} onChange={(e) => setPtp(e.target.value)} className="!mb-0" />}
            {isPaid && <Input label="Proof ref" value={proof} onChange={(e) => setProof(e.target.value)} placeholder="UTR / receipt" className="!mb-0" />}
            <button type="button" onClick={() => log.mutate()} disabled={log.isPending} className="btn btn-sm btn-navy disabled:opacity-50">
              {log.isPending && <Loader2 size={13} className="animate-spin" />} Log
            </button>
          </div>
          {/* A warning, not a block: the server accepts a promise without a date. */}
          {isPromise && !ptp && <p className="mt-2 text-xs text-warning-700">No promise-to-pay date — it will be logged without one.</p>}
          {log.error && <p className="mt-2 text-sm text-error-700">{errMessage(log.error)}</p>}
        </div>
      )}
      {loading ? (
        <Skeleton variant="line" rows={3} />
      ) : error ? (
        <ErrorState error={error} onRetry={onRetry} className="py-4" />
      ) : interactions.length === 0 ? (
        <EmptyState title="No interactions logged yet." className="py-4" />
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
    </Section>
  );
}

function AssignSection({ caseId, currentOfficerName, onAssigned }: { caseId: string; currentOfficerName: string | null; onAssigned: () => void }) {
  const officersQ = useQuery({ queryKey: ["collections-officers"], queryFn: collectionsApi.listOfficers });
  const [officerId, setOfficerId] = React.useState("");
  const assign = useMutation({
    mutationFn: () => collectionsApi.assignOfficer(caseId, Number(officerId)),
    onSuccess: () => {
      onAssigned();
      toast.success("Officer assigned");
    },
  });
  // Only ACTIVE staff are assignable.
  const officers = (officersQ.data ?? []).filter((o) => o.active);
  return (
    <Section title="Assign officer" icon={UserPlus}>
      <p className="mb-2 text-xs text-muted">Current: <span className="text-ink">{currentOfficerName ?? "—"}</span></p>
      <div className="flex flex-wrap items-end gap-3">
        <Select
          label="Officer (active executives)"
          value={officerId}
          onChange={(e) => setOfficerId(e.target.value)}
          options={[
            { value: "", label: officersQ.isLoading ? "Loading…" : "Select an officer" },
            ...officers.map((o) => ({ value: String(o.id), label: `${o.name} (${o.role})` })),
          ]}
          className="!mb-0"
        />
        <button type="button" onClick={() => assign.mutate()} disabled={assign.isPending || !officerId} className="btn btn-sm btn-navy disabled:opacity-50">
          {assign.isPending && <Loader2 size={13} className="animate-spin" />} Assign
        </button>
      </div>
      {officersQ.error && <p className="mt-2 text-xs text-error-700">{errMessage(officersQ.error)}</p>}
      {assign.error && <p className="mt-2 text-sm text-error-700">{errMessage(assign.error)}</p>}
    </Section>
  );
}

function SettlementSection({ caseId }: { caseId: string }) {
  const qc = useQueryClient();
  const [amount, setAmount] = React.useState("");
  // Null while empty, a lone ".", or zero — Propose stays disabled until it is an amount.
  const rupees = parsePositiveRupees(amount);
  const propose = useMutation({
    mutationFn: (value: number) => collectionsApi.proposeSettlement(caseId, rupeesToPaise(value)),
    onSuccess: () => {
      setAmount("");
      qc.invalidateQueries({ queryKey: ["collections-settlements"] });
    },
  });
  return (
    <Section title="Propose settlement" icon={HandCoins}>
      <div className="flex flex-wrap items-end gap-3">
        <Input
          label="Settlement amount (₹)"
          inputMode="decimal"
          value={amount}
          onChange={(e) => setAmount(sanitizeRupeeInput(e.target.value))}
          placeholder="25000"
          className="!mb-0"
        />
        <button type="button" onClick={() => rupees != null && propose.mutate(rupees)} disabled={propose.isPending || rupees == null} className="btn btn-sm btn-gold disabled:opacity-50">
          {propose.isPending && <Loader2 size={13} className="animate-spin" />} Propose
        </button>
      </div>
      {propose.error && <p className="mt-2 text-sm text-error-700">{errMessage(propose.error)}</p>}
      {propose.data?.settlementAmountPaise != null && (
        <p className="mt-2 text-sm text-success-700">Proposed {paiseToINR(propose.data.settlementAmountPaise)} — pending approval.</p>
      )}
      <p className="mt-2 text-xs text-muted">A Collection Head approves it on the Settlements page (separation of duties).</p>
    </Section>
  );
}

function CallsSection({ customerId, loanId }: { customerId: number; loanId: number }) {
  const q = useQuery({ queryKey: ["customer-call-logs", customerId], queryFn: () => customersApi.callLogs(customerId) });
  if (q.isLoading) return <Skeleton variant="row" />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const logs = q.data ?? [];
  const about = logs.filter((l) => l.loanId === loanId);
  const other = logs.filter((l) => l.loanId !== loanId);
  const list = (rows: typeof logs, empty: string) =>
    rows.length === 0 ? (
      <EmptyState title={empty} className="py-3" />
    ) : (
      <ul className="space-y-2">{rows.map((l) => <CallLogRow key={l.id} log={l} />)}</ul>
    );
  return (
    <>
      <Section title={`Calls about this loan (${about.length})`}>{list(about, "No calls tagged to this loan.")}</Section>
      <Section title={`Other customer calls (${other.length})`}>{list(other, "No other calls.")}</Section>
    </>
  );
}
