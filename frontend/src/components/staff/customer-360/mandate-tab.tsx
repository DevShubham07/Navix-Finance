"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, Check, Copy, Loader2, RefreshCw, Send, ShieldCheck, ShieldOff, XCircle } from "lucide-react";
import { Badge, EmptyState, Skeleton, toast } from "@/components/ui";
import { useCan, errMessage } from "@/components/staff/pipeline/hooks";
import {
  collectionsApi,
  featureFlagsApi,
  staffApi,
  type MandateAttempt,
  type MandateCancelReason,
} from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";

/** NPCI's ACK carries the UMRN; the destination bank's approval (`register_success`) follows a day or so later. */
const bankApproved = (a: MandateAttempt) => a.digioBankState === "register_success";

const STATUS_LABEL: Record<string, string> = {
  PENDING_AUTHORIZATION: "Awaiting borrower authorisation",
  ACTIVE: "Active",
  AUTHORIZATION_FAILED: "Authorisation failed",
  EXPIRED: "Expired",
  BANK_UNSUPPORTED: "Bank does not support online mandates",
};

/** Badge tone per mandate state — the status is the first thing staff need, so it leads the card. */
const STATUS_TONE: Record<string, "success" | "warning" | "error" | "neutral"> = {
  ACTIVE: "success",
  PENDING_AUTHORIZATION: "warning",
  AUTHORIZATION_FAILED: "error",
  EXPIRED: "error",
  BANK_UNSUPPORTED: "warning",
};

const inr2 = (paise: number) =>
  new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", minimumFractionDigits: 2 }).format(paise / 100);

/** Rupee text ("1250.5") to integer paise, or null when it is not a positive amount with at most 2 decimals. */
export function parseRupeesToPaise(text: string): number | null {
  const t = text.trim();
  if (!/^\d+(\.\d{1,2})?$/.test(t)) return null;
  const paise = Math.round(parseFloat(t) * 100);
  return paise > 0 ? paise : null;
}

/** Latest eMandate attempt. Only the last four of the account is ever shown; the auth link never is. */
export function MandateTab({
  applicationId,
  loanId = null,
  status,
  onChanged,
}: {
  applicationId: number | null;
  loanId?: number | null;
  status: string | null;
  onChanged?: () => void;
}) {
  const can = useCan();
  const qc = useQueryClient();
  const [notes, setNotes] = React.useState("");
  const q = useQuery({
    queryKey: ["staff-mandate", applicationId],
    queryFn: () => staffApi.mandate(applicationId as number),
    enabled: applicationId != null,
  });
  const override = useMutation({
    mutationFn: () => staffApi.mandateOverride(applicationId as number, notes.trim()),
    onSuccess: () => {
      toast.success("Mandate overridden; moved to disbursement");
      setNotes("");
      qc.invalidateQueries({ queryKey: ["staff-mandate", applicationId] });
      onChanged?.();
    },
  });

  // The case (and so the live outstanding + DPD) is only readable by staff allowed to work it; an executive
  // on someone else's case gets CASE_NOT_ASSIGNED here and simply sees no debit action.
  // The server reads digio-debit with defaultWhenMissing=FALSE, so the UI must too: require an explicit
  // true. Without this the box rendered whatever the flag said, and an officer filled in an amount, hit
  // confirm and got DEBIT_DISABLED — a disabled feature that looks exactly like a bug. Same query key as
  // the shell's, so react-query serves it from cache rather than refetching per tab.
  const { data: flags } = useQuery({ queryKey: ["feature-flags"], queryFn: () => featureFlagsApi.get() });
  const debitEnabled = flags?.["digio-debit"] === true;
  const mayDebit = can("mandate:debit") && loanId != null;
  // Same roles the backend allowlists on POST .../mandate/invite, plus the Disbursement Head.
  const mayInvite = can("mandate:debit") || can("mandate:override");
  const caseQ = useQuery({
    queryKey: ["staff-debit-case", loanId],
    queryFn: () => collectionsApi.caseByLoan(loanId as number),
    enabled: mayDebit && q.data?.status === "ACTIVE",
    retry: false,
  });
  // History is read whenever the loan exists, independent of the case and of the mandate being ACTIVE:
  // the outcome of real money has to stay visible after the loan closes or the flag is switched off.
  const historyQ = useQuery({
    queryKey: ["staff-debit-history", loanId],
    queryFn: () => collectionsApi.debits(loanId as number),
    enabled: mayDebit,
    retry: false,
  });
  const [amountText, setAmountText] = React.useState("");
  const [confirming, setConfirming] = React.useState(false);
  const debit = useMutation({
    mutationFn: (paise: number) => collectionsApi.debit(loanId as number, paise),
    onSuccess: (r) => {
      toast.success(`Debit of ${inr2(r.amountPaise)} requested`);
      setConfirming(false);
      qc.invalidateQueries({ queryKey: ["staff-debit-case", loanId] });
      qc.invalidateQueries({ queryKey: ["staff-debit-history", loanId] });
      onChanged?.();
    },
  });

  // A debit still SCHEDULED/IN_PROGRESS blocks cancelling the mandate. "Check status" asks the bank for its
  // outcome; "Cancel debit" withdraws it (the server asks the bank first, and Digio only allows a cancel
  // before the bank picks the debit up).
  const [cancelDebitId, setCancelDebitId] = React.useState<number | null>(null);
  const afterDebitChange = () => {
    qc.invalidateQueries({ queryKey: ["staff-debit-history", loanId] });
    qc.invalidateQueries({ queryKey: ["staff-debit-case", loanId] });
    onChanged?.();
  };
  const reconcileDebit = useMutation({
    mutationFn: (debitId: number) => collectionsApi.reconcileDebit(loanId as number, debitId),
    onSuccess: (r) => {
      toast.success(`Bank says: ${r.status}`);
      afterDebitChange();
    },
    onError: (e) => toast.error(errMessage(e)),
  });
  const cancelDebit = useMutation({
    mutationFn: (debitId: number) => collectionsApi.cancelDebit(loanId as number, debitId),
    onSuccess: (r) => {
      toast.success(r.status === "CANCELLED" ? "Debit cancelled" : `Not cancelled — the bank already reports ${r.status}`);
      setCancelDebitId(null);
      afterDebitChange();
    },
    onError: (e) => toast.error(errMessage(e)),
  });

  // Invite: for a customer who never saw the mandate step because their loan was already live. Files
  // nothing — it returns the link and asks the notification engine to send it on every channel.
  const [inviteLink, setInviteLink] = React.useState<string>();
  const [copied, setCopied] = React.useState(false);
  const invite = useMutation({
    mutationFn: () => staffApi.mandateInvite(applicationId as number),
    onSuccess: (r) => {
      setInviteLink(r.link);
      toast.success("Invite sent; link ready to copy");
    },
  });
  const copyLink = async () => {
    if (!inviteLink) return;
    try {
      await navigator.clipboard.writeText(inviteLink);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      toast.error("Couldn't copy — select the link and copy it manually");
    }
  };

  // Every attempt this customer has made, across applications — a mandate is a per-customer authority,
  // so a reborrower's earlier one may be the live one.
  const attemptsQ = useQuery({
    queryKey: ["staff-mandate-history", applicationId],
    queryFn: () => staffApi.mandateHistory(applicationId as number),
    enabled: applicationId != null,
  });
  // Re-read unsettled attempts from Digio. Files nothing. Exists because auth_failed is not terminal:
  // an attempt we recorded as failed may since have completed, and only a re-read reveals it.
  const refresh = useMutation({
    mutationFn: (_silent?: boolean) => staffApi.mandateRefresh(applicationId as number),
    onSuccess: (rows, silent) => {
      if (silent) {
        qc.invalidateQueries({ queryKey: ["staff-mandate-history", applicationId] });
        return;
      }
      const live = rows.find((r) => r.status === "ACTIVE");
      toast.success(live ? `Active mandate found — UMRN ${live.umrn}` : "Checked with the bank; nothing changed");
      qc.invalidateQueries({ queryKey: ["staff-mandate", applicationId] });
      qc.invalidateQueries({ queryKey: ["staff-mandate-history", applicationId] });
      onChanged?.();
    },
  });

  // Opening the tab re-checks, once, any active mandate the bank has not approved yet. Once approved the
  // backend stops asking Digio, so this costs nothing on a settled customer.
  const autoChecked = React.useRef(false);
  React.useEffect(() => {
    if (autoChecked.current || !mayInvite || !attemptsQ.data) return;
    if (attemptsQ.data.some((a) => a.status === "ACTIVE" && !a.cancelledAt && !bankApproved(a))) {
      autoChecked.current = true;
      refresh.mutate(true);
    }
  }, [attemptsQ.data, mayInvite, refresh]);

  // Cancelling withdraws a real registration at NPCI, so it is heads-only and always confirmed first.
  const mayCancel = can("mandate:override") || can("collections:manage");
  const [cancelId, setCancelId] = React.useState<number | null>(null);
  const cancel = useMutation({
    mutationFn: (mandateId: number) => staffApi.mandateCancel(applicationId as number, mandateId),
    onSuccess: () => {
      toast.success("Mandate cancelled");
      setCancelId(null);
      qc.invalidateQueries({ queryKey: ["staff-mandate", applicationId] });
      qc.invalidateQueries({ queryKey: ["staff-mandate-history", applicationId] });
      onChanged?.();
    },
    onError: (e) => toast.error(errMessage(e)),
  });

  // Cancelling the LIVE mandate: a deliberate act with a reason and a note, never a one-click row action.
  // Auto-debit stops for any loan it served (manual repayment from then on) and the borrower is notified.
  const [activeCancel, setActiveCancel] = React.useState<MandateAttempt | null>(null);
  const [cancelReason, setCancelReason] = React.useState<MandateCancelReason>("CUSTOMER_REQUEST");
  const [cancelNotes, setCancelNotes] = React.useState("");
  const cancelActive = useMutation({
    mutationFn: (a: MandateAttempt) =>
      staffApi.mandateCancelActive(applicationId as number, a.id, cancelReason, cancelNotes.trim()),
    onSuccess: () => {
      toast.success(
        cancelReason === "CORPORATE_REQUEST"
          ? "Mandate cancelled — send the customer an invite to set up the new one"
          : "Mandate cancelled; the customer has been notified",
      );
      setActiveCancel(null);
      setCancelNotes("");
      qc.invalidateQueries({ queryKey: ["staff-mandate", applicationId] });
      qc.invalidateQueries({ queryKey: ["staff-mandate-history", applicationId] });
      onChanged?.();
    },
  });
  const liveLoan = status === "ACTIVE" || status === "OVERDUE";

  const activeCancelPanel = activeCancel ? (
    <div className="max-w-2xl space-y-3 rounded-lg border border-error-100 bg-error-50 p-4">
      <div className="flex items-center gap-2 text-sm font-semibold text-error-700">
        <AlertTriangle size={15} /> Cancel the active mandate {activeCancel.umrn ? `(UMRN ${activeCancel.umrn})` : ""}
      </div>
      <p className="text-xs leading-relaxed text-navy">
        This revokes the customer&apos;s auto-repayment authority at their bank and cannot be undone — a new
        mandate needs them to authorise again.{" "}
        {liveLoan ? (
          <strong>This customer has a live loan: automatic debits stop now and it must be repaid manually.</strong>
        ) : (
          "Automatic debits stop for any loan this mandate served."
        )}{" "}
        The customer is notified by email and in the app.
      </p>
      <fieldset className="space-y-1 text-sm">
        <legend className="mb-1 text-xs text-muted">Reason</legend>
        <label className="flex items-center gap-2">
          <input
            type="radio"
            name="mandate-cancel-reason"
            checked={cancelReason === "CUSTOMER_REQUEST"}
            onChange={() => setCancelReason("CUSTOMER_REQUEST")}
          />
          The customer asked us to cancel it
        </label>
        <label className="flex items-center gap-2">
          <input
            type="radio"
            name="mandate-cancel-reason"
            checked={cancelReason === "CORPORATE_REQUEST"}
            onChange={() => setCancelReason("CORPORATE_REQUEST")}
          />
          Replacing it (e.g. a higher limit is needed)
        </label>
      </fieldset>
      <div>
        <label className="block text-xs text-muted" htmlFor="mandate-cancel-notes">Note (required)</label>
        <textarea
          id="mandate-cancel-notes"
          value={cancelNotes}
          onChange={(e) => setCancelNotes(e.target.value)}
          maxLength={480}
          rows={2}
          placeholder="e.g. Customer requested on call 22 Oct; or: limit too low for new sanction of ₹15,000"
          className="w-full rounded border border-line bg-white px-2 py-1.5 text-sm"
        />
      </div>
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          onClick={() => cancelActive.mutate(activeCancel)}
          disabled={cancelActive.isPending || cancelNotes.trim() === ""}
          className="btn btn-sm btn-navy disabled:opacity-50"
        >
          {cancelActive.isPending && <Loader2 size={14} className="animate-spin" />} Cancel mandate
        </button>
        <button
          type="button"
          onClick={() => setActiveCancel(null)}
          disabled={cancelActive.isPending}
          className="btn btn-sm"
        >
          Keep it
        </button>
      </div>
      {cancelActive.isError && <p className="text-xs text-error-700">{errMessage(cancelActive.error)}</p>}
    </div>
  ) : null;

  // Mandate ceiling: everyone on this tab sees what a NEW mandate will be registered for; only ADMIN may
  // raise it (V89). It never changes what the customer may borrow, and an existing mandate keeps its own.
  const isAdmin = can("customer:manage");
  const ceilingQ = useQuery({
    queryKey: ["staff-mandate-ceiling", applicationId],
    queryFn: () => staffApi.mandateCeiling(applicationId as number),
    enabled: applicationId != null,
  });
  const [ceilingText, setCeilingText] = React.useState("");
  const [ceilingNote, setCeilingNote] = React.useState("");
  const setCeiling = useMutation({
    mutationFn: (paise: number | null) =>
      staffApi.setMandateCeiling(applicationId as number, paise, ceilingNote.trim() || undefined),
    onSuccess: (v) => {
      toast.success(
        v.overridePaise != null
          ? `New mandates will be registered for ${inr2(v.registrationPaise ?? v.overridePaise)}`
          : "Mandate limit back to the standard formula",
      );
      setCeilingText("");
      setCeilingNote("");
      qc.invalidateQueries({ queryKey: ["staff-mandate-ceiling", applicationId] });
    },
    onError: (e) => toast.error(errMessage(e)),
  });
  const ceilingPaise = parseRupeesToPaise(ceilingText);
  const cv = ceilingQ.data;
  const ceilingPanel = cv ? (
    <div className="max-w-2xl space-y-2 rounded-lg border border-line bg-grey-50 p-4 text-sm">
      <div className="font-semibold text-navy">Mandate limit for new registrations</div>
      <p className="text-xs text-muted">
        A new mandate is registered for{" "}
        <strong className="text-navy">{cv.registrationPaise != null ? inr2(cv.registrationPaise) : "—"}</strong>
        {cv.overridePaise != null ? (
          <>
            {" "}— raised by an admin override of {inr2(cv.overridePaise)} (standard formula{" "}
            {cv.formulaPaise != null ? inr2(cv.formulaPaise) : "—"})
            {cv.overrideSetBy ? `, set by ${cv.overrideSetBy}` : ""}
            {cv.overrideSetAt ? ` on ${formatDateTime(cv.overrideSetAt)}` : ""}
            {cv.overrideNote ? ` — "${cv.overrideNote}"` : ""}.
          </>
        ) : (
          " (standard formula: 2 × the larger of the eligible limit and the sanctioned amount)."
        )}{" "}
        It does not change what the customer can borrow, and an existing mandate keeps the limit it was
        authorised for.
      </p>
      {isAdmin && (
        <div className="space-y-2">
          <div className="flex flex-wrap items-end gap-2">
            <div>
              <label className="block text-xs text-muted" htmlFor="mandate-ceiling">New mandate limit (₹)</label>
              <input
                id="mandate-ceiling"
                inputMode="decimal"
                value={ceilingText}
                onChange={(e) => setCeilingText(e.target.value)}
                placeholder="75000"
                className="w-40 rounded border border-line bg-white px-2 py-1.5 text-sm"
              />
            </div>
            <input
              aria-label="Reason (optional)"
              value={ceilingNote}
              onChange={(e) => setCeilingNote(e.target.value)}
              maxLength={500}
              placeholder="Reason (optional)"
              className="min-w-[12rem] flex-1 rounded border border-line bg-white px-2 py-1.5 text-sm"
            />
            <button
              type="button"
              onClick={() => ceilingPaise != null && setCeiling.mutate(ceilingPaise)}
              disabled={ceilingPaise == null || setCeiling.isPending}
              className="btn btn-sm btn-navy disabled:opacity-50"
            >
              {setCeiling.isPending && <Loader2 size={14} className="animate-spin" />} Set limit
            </button>
            {cv.overridePaise != null && (
              <button
                type="button"
                onClick={() => setCeiling.mutate(null)}
                disabled={setCeiling.isPending}
                className="btn btn-sm"
              >
                Remove override
              </button>
            )}
          </div>
          {ceilingText.trim() !== "" && ceilingPaise == null && (
            <p className="text-xs text-error-700">Enter an amount in rupees, up to 2 decimals.</p>
          )}
        </div>
      )}
    </div>
  ) : null;

  const refreshButton = mayInvite ? (
    <button
      type="button"
      onClick={() => refresh.mutate(false)}
      disabled={refresh.isPending}
      className="btn btn-sm disabled:opacity-50"
    >
      {refresh.isPending ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
      Re-check with bank
    </button>
  ) : null;

  const historyPanel =
    (attemptsQ.data?.length ?? 0) > 0 ? (
      <div className="max-w-3xl space-y-2">
        <h4 className="text-sm font-semibold text-navy">
          Mandate attempts <span className="font-normal text-muted">({attemptsQ.data?.length})</span>
        </h4>
        <div className="overflow-x-auto rounded-lg border border-line">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-line bg-grey-50 text-left text-muted">
                <th className="px-3 py-2 font-medium">#</th>
                <th className="px-3 py-2 font-medium">Status</th>
                <th className="px-3 py-2 font-medium">UMRN</th>
                <th className="px-3 py-2 font-medium">Mode</th>
                <th className="px-3 py-2 font-medium">Ceiling</th>
                <th className="px-3 py-2 font-medium">Started</th>
                <th className="px-3 py-2 font-medium">App</th>
                {mayCancel && <th className="px-3 py-2 font-medium" />}
              </tr>
            </thead>
            <tbody>
              {attemptsQ.data?.map((a: MandateAttempt) => (
                <tr
                  key={a.id}
                  className={`border-b border-line/60 last:border-0 ${a.reusable ? "bg-success-50" : ""}`}
                >
                  <td className="px-3 py-2">{a.attemptNo}</td>
                  <td className="px-3 py-2">
                    {a.cancelledAt ? "Cancelled" : STATUS_LABEL[a.status] ?? a.status}
                    {a.reusable && <span className="ml-1 font-semibold text-success-700">· in force</span>}
                    {a.status === "ACTIVE" && !a.cancelledAt && (
                      <div className={bankApproved(a) ? "text-success-700" : "text-warning-700"}>
                        {bankApproved(a) ? "Bank approved" : "Acknowledged — awaiting bank approval"}
                      </div>
                    )}
                    {a.cancelledAt ? (
                      <div className="text-muted">
                        {formatDateTime(a.cancelledAt)}
                        {a.cancelledBy ? ` by ${a.cancelledBy}` : ""}
                        {a.cancelReason ? ` — ${a.cancelReason}` : ""}
                      </div>
                    ) : (
                      a.rejectReason && <div className="text-muted">{a.rejectReason}</div>
                    )}
                  </td>
                  <td className="px-3 py-2 font-mono">{a.umrn ?? "—"}</td>
                  <td className="px-3 py-2">{a.authSubMode ?? a.digioState ?? "—"}</td>
                  <td className="px-3 py-2 font-mono">{inr2(a.maximumAmountPaise)}</td>
                  <td className="px-3 py-2">{a.createdAt ? formatDateTime(a.createdAt) : "—"}</td>
                  <td className="px-3 py-2 font-mono">{a.applicationId ? `#${a.applicationId}` : "—"}</td>
                  {mayCancel && (
                    <td className="px-3 py-2 text-right">
                      {/* An ACTIVE mandate opens the deliberate cancel panel below; a never-filed one has nothing to cancel. */}
                      {a.status === "ACTIVE" ? (
                        <button
                          type="button"
                          onClick={() => {
                            setActiveCancel(a);
                            setCancelNotes("");
                            setCancelReason("CUSTOMER_REQUEST");
                          }}
                          className="inline-flex items-center gap-1 whitespace-nowrap text-error-700 hover:underline"
                        >
                          <XCircle size={13} /> Cancel mandate…
                        </button>
                      ) : a.status === "BANK_UNSUPPORTED" || a.cancelledAt ? null : cancelId === a.id ? (
                        <span className="inline-flex items-center gap-1 whitespace-nowrap">
                          <span className="text-navy">Cancel this mandate?</span>
                          <button
                            type="button"
                            onClick={() => cancel.mutate(a.id)}
                            disabled={cancel.isPending}
                            className="btn btn-sm btn-navy disabled:opacity-50"
                          >
                            {cancel.isPending && <Loader2 size={12} className="animate-spin" />} Yes, cancel
                          </button>
                          <button
                            type="button"
                            onClick={() => setCancelId(null)}
                            disabled={cancel.isPending}
                            className="btn btn-sm"
                          >
                            No
                          </button>
                        </span>
                      ) : (
                        <button
                          type="button"
                          onClick={() => setCancelId(a.id)}
                          className="inline-flex items-center gap-1 text-error-700 hover:underline"
                        >
                          <XCircle size={13} /> Cancel
                        </button>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {activeCancelPanel}
        {(attemptsQ.data?.filter((a) => a.status === "PENDING_AUTHORIZATION").length ?? 0) > 1 && (
          <p className="text-xs text-warning-800">
            More than one attempt is still awaiting authorisation. Each is a live mandate request at the
            bank — if the customer completes one of the older ones they get a second UMRN. Ask them to
            close any open bank tabs.
          </p>
        )}
      </div>
    ) : null;

  // Rendered BOTH with a mandate on file and without one. The no-mandate case is exactly the cohort an
  // invite is for — a DISBURSEMENT_PENDING or live application that never reached the step — and the
  // first cut of this panel sat after the `if (!m) return <EmptyState/>` early return, so it was
  // invisible to precisely the people who needed it.
  const invitePanel = mayInvite ? (
        <div className="max-w-2xl space-y-2 rounded-lg border border-line bg-grey-50 p-4">
          <div className="flex items-center gap-2 text-sm font-semibold text-navy">
            <Send size={15} /> Invite the customer to set up auto-repayment
          </div>
          <p className="text-xs leading-relaxed text-muted">
            Sends the customer a link on every channel they have, and gives you the same link to copy.
            Nothing is filed until they open it and authorise with their bank, so this is safe to repeat.
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => invite.mutate()}
              disabled={invite.isPending}
              className="btn btn-sm btn-navy disabled:opacity-50"
            >
              {invite.isPending && <Loader2 size={14} className="animate-spin" />} Send invite
            </button>
            {inviteLink && (
              <button type="button" onClick={() => void copyLink()} className="btn btn-sm">
                {copied ? <Check size={14} className="text-success-600" /> : <Copy size={14} />}
                {copied ? "Copied" : "Copy link"}
              </button>
            )}
          </div>
          {inviteLink && (
            <p className="break-all rounded border border-line bg-white px-2 py-1.5 font-mono text-[11px] text-muted">
              {inviteLink}
            </p>
          )}
          {invite.isError && <p className="text-xs text-error-700">{errMessage(invite.error)}</p>}
        </div>
  ) : null;

  if (applicationId == null) {
    return <EmptyState icon={<ShieldOff size={28} />} title="No application yet." hint="A mandate is registered after sanction." />;
  }
  if (q.isLoading) return <Skeleton className="h-24 w-full" />;
  if (q.isError) return <p className="text-sm text-error-700">{errMessage(q.error)}</p>;
  const m = q.data;
  if (!m) {
    return (
      <div className="space-y-5">
        <EmptyState
          icon={<ShieldOff size={28} />}
          title="No mandate started."
          hint={
            mayInvite
              ? "The borrower registers it in the offer journey — or send them the link below."
              : "The borrower registers it in the offer journey."
          }
        />
        {invitePanel}
        {ceilingPanel}
        {refreshButton && <div>{refreshButton}</div>}
        {historyPanel}
      </div>
    );
  }

  const rows: [string, string][] = [
    ["Bank", m.bankName ?? "—"],
    ["Account", m.accountLast4 ? `•••• ${m.accountLast4}` : "—"],
    ["Authorised", m.authenticatedAt ? formatDateTime(m.authenticatedAt) : "—"],
    ["Attempt", String(m.attemptNo)],
  ];
  if (m.rejectReason) rows.push(["Reject reason", m.rejectReason]);

  const inForce = attemptsQ.data?.find((a) => a.reusable);
  const dc = caseQ.data;
  const outstanding = dc?.loan?.outstandingPaise ?? null;
  const showDebit = debitEnabled && mayDebit && m.status === "ACTIVE" && !!m.accountLast4 && dc != null && outstanding != null && outstanding > 0 && dc.dpd >= 1;
  const amountPaise = parseRupeesToPaise(amountText);
  const amountError =
    amountText.trim() === "" ? null
    : amountPaise == null ? "Enter an amount in rupees, up to 2 decimals."
    : outstanding != null && amountPaise > outstanding ? `It cannot be more than the outstanding ${inr2(outstanding)}.`
    : null;

  return (
    <div className="space-y-5">
      <div className="max-w-2xl overflow-hidden rounded-lg border border-line bg-white shadow-sm">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line bg-grey-50 px-4 py-3">
          <div className="flex items-center gap-2">
            <ShieldCheck size={16} className={m.status === "ACTIVE" ? "text-success-600" : "text-muted"} />
            <span className="text-sm font-semibold text-navy">Auto-repayment mandate</span>
          </div>
          {m.cancelledAt ? (
            <Badge variant="error">Cancelled</Badge>
          ) : (
            <span className="flex items-center gap-1.5">
              <Badge variant={STATUS_TONE[m.status] ?? "neutral"}>{STATUS_LABEL[m.status] ?? m.status}</Badge>
              {m.status === "ACTIVE" && inForce && (
                <Badge variant={bankApproved(inForce) ? "success" : "warning"}>
                  {bankApproved(inForce) ? "Bank approved" : "Awaiting bank approval"}
                </Badge>
              )}
            </span>
          )}
        </div>

        {m.umrn ? (
          <div className="border-b border-line px-4 py-3">
            <div className="text-[11px] uppercase tracking-wide text-muted">UMRN</div>
            <div className="mt-0.5 font-mono text-sm font-semibold text-navy">{m.umrn}</div>
          </div>
        ) : null}

        <dl className="grid grid-cols-[8rem_1fr] gap-y-2 px-4 py-3 text-sm">
          {rows.map(([k, v]) => (
            <React.Fragment key={k}>
              <dt className="text-muted">{k}</dt>
              <dd className="font-medium text-navy">{v}</dd>
            </React.Fragment>
          ))}
        </dl>

        {m.carriedOver ? (
          <p className="border-t border-line bg-grey-50 px-4 py-2 text-xs text-muted">
            Carried over from an earlier advance — not registered on this application.
          </p>
        ) : null}
      </div>


      {refreshButton && <div className="flex flex-wrap gap-2">{refreshButton}</div>}

      {m.status !== "ACTIVE" ? invitePanel : null}
      {ceilingPanel}

      {showDebit && (
        <div className="max-w-md space-y-2 rounded border border-line bg-grey-50 p-3">
          <p className="text-xs text-muted">
            Debit the borrower&apos;s bank account now under this mandate. This takes real money and cannot be
            undone. Outstanding today: <strong>{inr2(outstanding as number)}</strong>. The amount can be lowered, never raised.
          </p>
          {!confirming ? (
            <>
              <label className="block text-xs text-muted" htmlFor="debit-amount">Amount to debit (₹)</label>
              <input
                id="debit-amount"
                inputMode="decimal"
                value={amountText}
                onChange={(e) => setAmountText(e.target.value)}
                placeholder={((outstanding as number) / 100).toFixed(2)}
                className="w-full rounded border border-line px-2 py-1.5 text-sm"
              />
              {amountError && <p className="text-xs text-error-700">{amountError}</p>}
              <button
                type="button"
                onClick={() => {
                  if (amountText.trim() === "") setAmountText(((outstanding as number) / 100).toFixed(2));
                  setConfirming(true);
                }}
                disabled={!!amountError}
                className="btn btn-sm btn-navy disabled:opacity-50"
              >
                Review debit
              </button>
            </>
          ) : (
            <>
              <p className="text-sm text-navy" role="alert">
                Debit <strong>{inr2(amountPaise ?? (outstanding as number))}</strong> from the account ending{" "}
                <strong>•••• {m.accountLast4}</strong>?
              </p>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => debit.mutate(amountPaise ?? (outstanding as number))}
                  disabled={debit.isPending}
                  className="btn btn-sm btn-navy disabled:opacity-50"
                >
                  {debit.isPending && <Loader2 size={14} className="animate-spin" />} Confirm and debit
                </button>
                <button type="button" onClick={() => setConfirming(false)} disabled={debit.isPending} className="btn btn-sm">
                  Cancel
                </button>
              </div>
            </>
          )}
          {debit.isError && <p className="text-xs text-error-700">{errMessage(debit.error)}</p>}
        </div>
      )}

      {mayDebit && (historyQ.data?.length ?? 0) > 0 && (
        <div className="max-w-2xl space-y-2">
          <h4 className="text-sm font-semibold text-navy">Debit attempts</h4>
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-line text-left text-muted">
                <th className="py-1.5 pr-3 font-medium">#</th>
                <th className="py-1.5 pr-3 font-medium">Status</th>
                <th className="py-1.5 pr-3 font-medium">Amount</th>
                <th className="py-1.5 pr-3 font-medium">Settlement</th>
                <th className="py-1.5 pr-3 font-medium">UTR</th>
                <th className="py-1.5 pr-3 font-medium">Trigger</th>
                <th className="py-1.5 font-medium" />
              </tr>
            </thead>
            <tbody>
              {historyQ.data?.map((d) => (
                <tr key={d.debitId} className="border-b border-line/60 last:border-0">
                  <td className="py-1.5 pr-3">{d.attemptNo}</td>
                  <td className="py-1.5 pr-3">
                    {d.status}
                    {/* Real money taken from a borrower who had already repaid — a refund is owed. */}
                    {d.overpayment && <span className="ml-1 font-semibold text-error-700">· refund owed</span>}
                    {d.status === "FAILED" && !d.representable && (
                      <span className="ml-1 text-muted">· not retryable</span>
                    )}
                  </td>
                  <td className="py-1.5 pr-3 font-mono">{inr2(d.amountPaise)}</td>
                  <td className="py-1.5 pr-3">{d.settlementDate ?? "—"}</td>
                  <td className="py-1.5 pr-3 font-mono">{d.utr ?? "—"}</td>
                  <td className="py-1.5 pr-3">{d.trigger ?? "—"}</td>
                  <td className="py-1.5 text-right">
                    {d.status === "SCHEDULED" || d.status === "IN_PROGRESS" ? (
                      cancelDebitId === d.debitId ? (
                        <span className="inline-flex items-center gap-1 whitespace-nowrap">
                          <span className="text-navy">Cancel this debit?</span>
                          <button
                            type="button"
                            onClick={() => cancelDebit.mutate(d.debitId)}
                            disabled={cancelDebit.isPending}
                            className="btn btn-sm btn-navy disabled:opacity-50"
                          >
                            {cancelDebit.isPending && <Loader2 size={12} className="animate-spin" />} Yes, cancel
                          </button>
                          <button
                            type="button"
                            onClick={() => setCancelDebitId(null)}
                            disabled={cancelDebit.isPending}
                            className="btn btn-sm"
                          >
                            No
                          </button>
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-3 whitespace-nowrap">
                          <button
                            type="button"
                            onClick={() => reconcileDebit.mutate(d.debitId)}
                            disabled={reconcileDebit.isPending}
                            className="inline-flex items-center gap-1 text-navy hover:underline disabled:opacity-50"
                          >
                            {reconcileDebit.isPending ? <Loader2 size={12} className="animate-spin" /> : <RefreshCw size={12} />}
                            Check status
                          </button>
                          <button
                            type="button"
                            onClick={() => setCancelDebitId(d.debitId)}
                            className="inline-flex items-center gap-1 text-error-700 hover:underline"
                          >
                            <XCircle size={12} /> Cancel debit
                          </button>
                        </span>
                      )
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {historyPanel}

      {m.overridable && status === "SANCTIONED" && can("mandate:override") && (
        <div className="max-w-md space-y-2 rounded border border-line bg-grey-50 p-3">
          <p className="text-xs text-muted">
            This bank cannot register an online mandate, so the borrower cannot proceed by retrying. Overriding
            sends the file to disbursement without a mandate. This action is logged.
          </p>
          <textarea
            aria-label="Reason for overriding the mandate"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            placeholder="Why you're overriding (required, goes in the audit trail)"
            rows={2}
            className="w-full rounded border border-line px-2 py-1.5 text-sm"
          />
          <button
            type="button"
            onClick={() => override.mutate()}
            disabled={override.isPending || !notes.trim()}
            className="btn btn-sm btn-navy disabled:opacity-50"
          >
            {override.isPending && <Loader2 size={14} className="animate-spin" />} Override mandate
          </button>
          {override.isError && <p className="text-xs text-error-700">{errMessage(override.error)}</p>}
        </div>
      )}
    </div>
  );
}
