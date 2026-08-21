"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2, X } from "lucide-react";
import { Dialog, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { PaymentProofLink } from "@/components/ui/payment-proof-link";
import { Tabs, type TabDef } from "@/components/ui/tabs";
import { LoanBreakdown } from "@/components/staff/loan-breakdown";
import { EventTimeline } from "@/components/staff/event-timeline";
import { CallLogRow, DocumentsTab, KV, Section } from "@/components/staff/detail-parts";
import { daysBetween } from "@/lib/calc/loan-math";
import {
  staffApi,
  customersApi,
  collectionsApi,
  paiseToINR,
  type LoanView,
  type PaymentView,
  type OutstandingView,
  type InteractionView,
} from "@/lib/api/applications";
import { formatDate } from "@/lib/utils";

const todayISO = () => new Date().toISOString().slice(0, 10);

/** "1" -> "1st", "2" -> "2nd", "3" -> "3rd", "11"/"12"/"13" -> "th", else "th". */
function ordinal(n: number): string {
  const rem100 = n % 100;
  if (rem100 >= 11 && rem100 <= 13) return `${n}th`;
  switch (n % 10) {
    case 1:
      return `${n}st`;
    case 2:
      return `${n}nd`;
    case 3:
      return `${n}rd`;
    default:
      return `${n}th`;
  }
}

/**
 * Mask a PAN the way the backend masks `LoanRegisterRow.panMasked`/`LoanSummary.panMasked`
 * (`ABCDE1234F` -> `ABCXX1234X`): keep the first 3 chars and the middle 4 digits, blank the
 * two chars before them and the final char. `customersApi.get` returns the borrower's PAN
 * unmasked (staff are trusted with the full profile elsewhere), but this modal's header must
 * never render it raw.
 */
function maskPan(pan: string | null | undefined): string | null {
  if (!pan) return null;
  if (pan.length !== 10) return pan.length <= 4 ? pan : `${pan.slice(0, 2)}${"X".repeat(pan.length - 2)}`;
  return `${pan.slice(0, 3)}XX${pan.slice(5, 9)}X`;
}

const TABS: TabDef[] = [
  { key: "overview", label: "Overview" },
  { key: "repayments", label: "Repayments" },
  { key: "documents", label: "Documents" },
  { key: "calls", label: "Calls & references" },
  { key: "timeline", label: "Timeline" },
];

/**
 * The product's single, full loan detail modal — read-only. Opened from the customer modal's
 * Loans tab, from loan history, and from the `/staff/loans` register (a pinned contract: that
 * page is written against exactly this `{ loanId, onClose }` prop shape).
 *
 * `loanId == null` is the closed convention (mirrors `CustomerDetailDialog`). Everything else —
 * the application, the customer, the borrower identity, the collections case — is resolved from
 * the loan id itself, so a caller only ever needs the one id.
 */
export function LoanDetailDialog({
  loanId,
  onClose,
}: {
  loanId: number | null;
  onClose: () => void;
}) {
  const open = loanId != null;
  const [tab, setTab] = React.useState("overview");

  const loanQ = useQuery({
    queryKey: ["staff-loan", loanId],
    queryFn: () => staffApi.loan(loanId as number),
    enabled: open,
    retry: false,
  });
  const loan = loanQ.data;
  const customerId = loan?.customerId ?? null;

  // Resolving the application, the borrower identity and the loan cycle all come off the same
  // customer roll-up, same as both prior call sites did by hand
  // (`c.applications.find(a => a.loanId === loanId)?.id`) — done once, here, instead of twice.
  const customerQ = useQuery({
    queryKey: ["customer-detail", customerId],
    queryFn: () => customersApi.get(customerId as number),
    enabled: open && customerId != null,
    retry: false,
  });
  const customer = customerQ.data;
  const app = customer?.applications.find((a) => a.loanId === loanId) ?? null;
  const applicationId = app?.id ?? null;
  const profile = customer?.profile;

  const outQ = useQuery({
    queryKey: ["staff-loan-out", loanId, todayISO()],
    queryFn: () => staffApi.outstanding(loanId as number, todayISO()),
    enabled: open,
    retry: false,
  });
  const payQ = useQuery({
    queryKey: ["staff-loan-pay", loanId],
    queryFn: () => staffApi.repayments(loanId as number),
    enabled: open,
  });
  const evQ = useQuery({
    queryKey: ["staff-events", applicationId],
    queryFn: () => staffApi.events(applicationId as number),
    enabled: open && applicationId != null,
  });
  // The collections officer assigned to this loan, when a case has been opened — shared by the
  // Overview "Assigned officer" row and the Calls tab's interaction log, same cache entry.
  const caseQ = useQuery({
    queryKey: ["staff-case-by-loan", loanId],
    queryFn: () => collectionsApi.caseByLoan(loanId as number),
    enabled: open,
    retry: false,
  });

  const out = outQ.data;
  const payments = payQ.data ?? [];
  const events = evQ.data ?? [];

  // This loan's 1-based position in the customer's own loan history ("2nd advance"), sorted
  // oldest-first by id — loan ids are assigned in disbursal order, so this is a stable ordinal
  // even for loans that share a due date or were backdated in a demo seed.
  const cycle = React.useMemo(() => {
    if (!customer || loanId == null) return null;
    const ordered = [...customer.loans].sort((a, b) => a.id - b.id);
    const idx = ordered.findIndex((l) => l.id === loanId);
    return idx >= 0 ? idx + 1 : null;
  }, [customer, loanId]);

  return (
    // !max-w / !w: globals.css's un-layered `.modal { max-width: 460px }` outranks plain
    // utilities in the cascade (mirrors customer-detail-dialog.tsx).
    <Dialog open={open} onClose={onClose} className="!max-w-4xl !w-[min(56rem,94vw)]">
      <DialogHeader>
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <DialogTitle>
              {profile?.fullName ?? (customerId != null ? `Customer #${customerId}` : "Loan")}{" "}
              <span className="text-sm font-normal text-muted">— Loan #{loanId}</span>
            </DialogTitle>
            <div className="mt-0.5 flex flex-wrap items-center gap-2 text-xs text-muted">
              {profile?.mobile && <span className="font-mono">{profile.mobile}</span>}
              {profile?.pan && <span>· PAN {maskPan(profile.pan)}</span>}
              {loan && (
                <span className="rounded-full bg-navy-tint px-2 py-0.5 font-semibold text-navy">
                  {loan.status}
                </span>
              )}
            </div>
          </div>
          <button onClick={onClose} aria-label="Close" className="flex-shrink-0 text-muted hover:text-ink">
            <X size={18} />
          </button>
        </div>
      </DialogHeader>

      <Tabs tabs={TABS} active={tab} onChange={setTab} className="mt-2" />

      <div className="mt-3 max-h-[70vh] space-y-6 overflow-y-auto pr-1 text-sm">
        {loanQ.isLoading ? (
          <p className="flex items-center gap-2 py-8 text-sm text-muted">
            <Loader2 size={15} className="animate-spin" /> Loading…
          </p>
        ) : loanQ.error || !loan ? (
          <p className="py-8 text-sm text-error-700">Could not load this loan.</p>
        ) : (
          <>
            {tab === "overview" && (
              <OverviewTab
                loan={loan}
                out={out}
                cycle={cycle}
                sanctionedAt={app?.sanctionedAt ?? null}
                salaryCreditDay={app?.salaryCreditDay ?? null}
                sanctionedAmountPaise={app?.sanctionedAmountPaise ?? null}
                collectionsOfficerName={caseQ.data?.assignedOfficerName ?? null}
                creditExecutiveName={app?.assignedExecutiveName ?? null}
              />
            )}

            {tab === "repayments" && (
              <section>
                <h4 className="mb-2 flex items-center gap-2 font-semibold text-ink">
                  How they paid
                  {payQ.isLoading && <Loader2 size={12} className="animate-spin text-muted" />}
                </h4>
                {payments.length === 0 ? (
                  <p className="text-muted">No repayments recorded yet.</p>
                ) : (
                  <ul className="divide-y divide-line rounded border border-line">
                    {payments.map((p) => (
                      <PaymentLi key={p.id} p={p} />
                    ))}
                  </ul>
                )}
              </section>
            )}

            {tab === "documents" &&
              (applicationId != null ? (
                <DocumentsTab applicationId={applicationId} />
              ) : (
                <p className="text-muted">No application on record for this loan.</p>
              ))}

            {tab === "calls" && customerId != null && (
              <CallsTab
                customerId={customerId}
                loanId={loanId as number}
                applicationId={applicationId}
                caseId={caseQ.data?.id ?? null}
                caseLoading={caseQ.isLoading}
              />
            )}

            {tab === "timeline" &&
              (applicationId != null ? (
                events.length === 0 ? (
                  <p className="text-muted">{evQ.isLoading ? "Loading…" : "No events recorded."}</p>
                ) : (
                  <EventTimeline events={events} />
                )
              ) : (
                <p className="text-muted">No application on record for this loan.</p>
              ))}
          </>
        )}
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------------------
// Overview
// ---------------------------------------------------------------------------

function OverviewTab({
  loan,
  out,
  cycle,
  sanctionedAt,
  salaryCreditDay,
  sanctionedAmountPaise,
  collectionsOfficerName,
  creditExecutiveName,
}: {
  loan: LoanView;
  out: OutstandingView | undefined;
  cycle: number | null;
  sanctionedAt: string | null;
  salaryCreditDay: number | null;
  sanctionedAmountPaise: number | null;
  collectionsOfficerName: string | null;
  creditExecutiveName: string | null;
}) {
  // DPD is compute-on-read everywhere in this product; never stored (mirrors `CollectionsFocus`
  // in application-detail-dialog.tsx).
  // Measured to the closing date on a settled loan, not to today — otherwise a loan repaid on
  // time reads as months overdue, and the number keeps growing forever after closure.
  const dpd = loan.dueDate
    ? Math.max(0, daysBetween(new Date(loan.dueDate), new Date(loan.closedOn ?? Date.now())))
    : 0;
  // `loan.closedOn` is the real stored column (V50), set when the balance actually reached zero.
  // Don't derive it from the last verified payment: a loan can close on an approved settlement or a
  // write-off, where no payment carries the closing date.
  const closedOn = loan.closedOn;

  return (
    <div className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2">
        <Section title="Dates">
          <KV k="Sanctioned" v={sanctionedAt ? formatDate(sanctionedAt) : null} />
          <KV k="Disbursed" v={loan.disbursedOn ? formatDate(loan.disbursedOn) : null} />
          <KV k="Salary date" v={salaryCreditDay != null ? `Day ${salaryCreditDay} of the month` : null} />
          <KV
            k="Due date"
            v={
              loan.dueDate ? (
                <>
                  {formatDate(loan.dueDate)} <span className="text-xs text-muted">— due on your salary day</span>
                </>
              ) : null
            }
          />
          <KV k="Closed" v={closedOn ? formatDate(closedOn) : null} />
          <KV k="DPD" v={loan.dueDate ? String(dpd) : null} />
        </Section>

        <Section title="Loan">
          <KV k="Status" v={loan.status} />
          <KV k="Cycle" v={cycle != null ? `${ordinal(cycle)} advance` : null} />
          <KV
            k="Sanctioned amount"
            v={paiseToINR(sanctionedAmountPaise ?? loan.principalPaise)}
          />
          <KV k="Collections officer" v={collectionsOfficerName} />
          <KV k="Credit executive" v={creditExecutiveName} />
          <KV k="Disbursal txn ref" v={loan.disbursalTxnRef} mono />
        </Section>
      </div>

      <LoanBreakdown loan={loan} outstanding={out} />
    </div>
  );
}

function PaymentLi({ p }: { p: PaymentView }) {
  const tone =
    p.status === "VERIFIED"
      ? "bg-success-50 text-success-700"
      : p.status === "REJECTED"
        ? "bg-error-50 text-error-700"
        : "bg-gold-50 text-gold-dark";
  return (
    <li className="flex items-center justify-between gap-2 px-3 py-2">
      <span>
        <span className="font-semibold text-ink">{paiseToINR(p.amountPaise)}</span>{" "}
        <span className="text-xs text-muted">
          {p.method === "BANK_TRANSFER" ? "Bank" : p.method}
          {p.txnRef ? ` · ${p.txnRef}` : ""}
          {p.paidOn ? ` · ${formatDate(p.paidOn)}` : ""}
          {p.partial ? " · partial" : ""}
        </span>{" "}
        <PaymentProofLink url={p.proofUrl} className="text-xs" />
      </span>
      <span className={`rounded-full px-2 py-0.5 text-xs font-semibold capitalize ${tone}`}>
        {p.status.replace(/_/g, " ").toLowerCase()}
      </span>
    </li>
  );
}

// ---------------------------------------------------------------------------
// Calls & references
// ---------------------------------------------------------------------------

const RELATION_LABEL: Record<string, string> = {
  PARENT: "Parent", SPOUSE: "Spouse", SIBLING: "Sibling", RELATIVE: "Relative",
  FRIEND: "Friend", COLLEAGUE: "Colleague", MANAGER: "Manager", NEIGHBOUR: "Neighbour",
};

/**
 * Everyone you'd ring about this loan, in the order you'd reach for them: the two personal
 * references the borrower named on the offer journey, then calls actually logged against this
 * loan, then the customer's older untagged calls (kept visibly separate — they predate loan
 * tagging and are not necessarily about this loan), then the collections case's own interaction
 * log when one has been opened.
 */
function CallsTab({
  customerId,
  loanId,
  applicationId,
  caseId,
  caseLoading,
}: {
  customerId: number;
  loanId: number;
  applicationId: number | null;
  caseId: string | null;
  caseLoading: boolean;
}) {
  const refQ = useQuery({
    queryKey: ["staff-references", applicationId],
    queryFn: () => staffApi.references(applicationId as number),
    enabled: applicationId != null,
    retry: false,
  });
  // Same "customer-call-logs" key the Calls tab (customer-tabs.tsx) uses for the unfiltered list —
  // fetched once here and filtered client-side into tagged/untagged so opening both tabs shares
  // the cache instead of doubling the request.
  const allCallsQ = useQuery({
    queryKey: ["customer-call-logs", customerId],
    queryFn: () => customersApi.callLogs(customerId),
  });
  const interactionsQ = useQuery({
    queryKey: ["staff-case-interactions", caseId],
    queryFn: () => collectionsApi.listInteractions(caseId as string),
    enabled: caseId != null,
  });

  const references = refQ.data ?? [];
  const allCalls = allCallsQ.data ?? [];
  const taggedCalls = allCalls.filter((c) => c.loanId === loanId);
  const untaggedCalls = allCalls.filter((c) => c.loanId == null);

  return (
    <div className="space-y-4">
      <Section title="References">
        {refQ.isLoading ? (
          <p className="text-muted">Loading…</p>
        ) : references.length === 0 ? (
          <p className="text-muted">No references on file.</p>
        ) : (
          <dl className="grid gap-x-6 gap-y-1 sm:grid-cols-2">
            {references.map((r) => (
              <KV
                key={r.slot}
                k={`${RELATION_LABEL[r.relation] ?? r.relation} · ${r.fullName}`}
                v={r.mobile}
                mono
              />
            ))}
          </dl>
        )}
      </Section>

      <Section title={`Calls about this loan (${taggedCalls.length})`}>
        {allCallsQ.isLoading ? (
          <p className="text-muted">Loading…</p>
        ) : taggedCalls.length === 0 ? (
          <p className="text-muted">No calls tagged to this loan yet.</p>
        ) : (
          <ul className="space-y-2">
            {taggedCalls.map((c) => (
              <CallLogRow key={c.id} log={c} />
            ))}
          </ul>
        )}
      </Section>

      <Section title={`Other customer calls (${untaggedCalls.length})`}>
        <p className="mb-2 text-xs text-muted">
          Not tagged to a specific loan — mostly calls logged before loan tagging existed.
        </p>
        {allCallsQ.isLoading ? (
          <p className="text-muted">Loading…</p>
        ) : untaggedCalls.length === 0 ? (
          <p className="text-muted">None.</p>
        ) : (
          <ul className="space-y-2">
            {untaggedCalls.map((c) => (
              <CallLogRow key={c.id} log={c} />
            ))}
          </ul>
        )}
      </Section>

      <Section title="Collection interactions">
        {caseLoading ? (
          <p className="text-muted">Loading…</p>
        ) : caseId == null ? (
          <p className="text-muted">No collections case has been opened for this loan.</p>
        ) : interactionsQ.isLoading ? (
          <p className="text-muted">Loading…</p>
        ) : (interactionsQ.data ?? []).length === 0 ? (
          <p className="text-muted">No interactions logged on this case yet.</p>
        ) : (
          <ul className="space-y-2">
            {(interactionsQ.data as InteractionView[]).map((i) => (
              <li key={i.id} className="rounded border border-line p-2.5 text-sm">
                <span className="font-semibold text-ink">{i.type}</span>{" "}
                <span className="text-muted">· {i.outcome}</span>
                {i.promiseToPayDate ? <span className="text-muted"> · PTP {i.promiseToPayDate}</span> : null}
                {i.proofRef ? <span className="text-muted"> · proof {i.proofRef}</span> : null}
                <span className="mt-1 block text-[8.8px] text-muted">
                  {i.loggedAt ? formatDate(i.loggedAt) : ""}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  );
}
