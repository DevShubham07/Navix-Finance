"use client";

import * as React from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Loader2, RefreshCw, Phone, HandCoins, UserPlus } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage } from "@/components/staff/live-pipeline";
import { collectionsApi, paiseToINR, rupeesToPaise, type InteractionView } from "@/lib/api/applications";
import { formatDate } from "@/lib/utils";

const TYPES = ["CALL", "SMS", "EMAIL", "VISIT"];
const OUTCOMES = ["CONNECTED", "NO_ANSWER", "PROMISE_TO_PAY", "PAID", "DISPUTED"];

/**
 * Collections case detail. The route param is the collection-case id (UUID).
 * Log interactions, assign an officer, and propose a settlement.
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
                <dt className="text-muted">Bucket</dt><dd className="text-right"><span className="rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy">{c.currentBucket}</span></dd>
                <dt className="text-muted">Loan (UUID)</dt><dd className="text-right font-mono text-xs text-ink">{c.loanId}</dd>
                <dt className="text-muted">Assigned officer</dt><dd className="text-right font-mono text-xs text-ink">{c.assignedOfficerId ?? "—"}</dd>
                <dt className="text-muted">Opened</dt><dd className="text-right text-ink">{c.createdAt ? formatDate(c.createdAt) : "—"}</dd>
              </dl>
            </div>

            <InteractionsCard
              caseId={caseId}
              interactions={interQ.data ?? []}
              loading={interQ.isLoading}
              onLogged={invalidate}
            />
          </div>

          <div className="space-y-6">
            <AssignCard caseId={caseId} onAssigned={invalidate} />
            <SettlementCard caseId={caseId} />
          </div>
        </div>
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
              <span className="flex-shrink-0 text-xs text-muted">{i.loggedAt ? formatDate(i.loggedAt) : ""}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function AssignCard({ caseId, onAssigned }: { caseId: string; onAssigned: () => void }) {
  const [officerId, setOfficerId] = React.useState("");
  const assign = useMutation({
    mutationFn: () => collectionsApi.assignOfficer(caseId, officerId.trim()),
    onSuccess: onAssigned,
  });
  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-navy"><UserPlus size={16} /> Assign officer</div>
      <Input label="Officer id (UUID)" value={officerId} onChange={(e) => setOfficerId(e.target.value)} placeholder="officer UUID" />
      <button
        type="button"
        onClick={() => { try { setOfficerId(crypto.randomUUID()); } catch { /* no crypto */ } }}
        className="mb-2 text-xs font-semibold text-navy hover:underline"
      >
        Generate a demo id
      </button>
      {assign.error && <p className="mb-2 text-sm text-error-700">{errMessage(assign.error)}</p>}
      <button onClick={() => assign.mutate()} disabled={assign.isPending || !officerId.trim()} className="btn btn-sm btn-navy btn-block disabled:opacity-50">
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
