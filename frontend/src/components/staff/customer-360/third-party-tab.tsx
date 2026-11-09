"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, Link2, Loader2, Radar, RotateCcw } from "lucide-react";
import { Badge, ConfirmDialog, EmptyState, ErrorState, Skeleton, toast } from "@/components/ui";
import { CreditProfileCard } from "@/components/staff/credit-profile-card";
import { CreditScoreGauge } from "@/components/staff/credit-score-gauge";
import { SkipTracePanel } from "@/components/staff/skip-trace-panel";
import { ResumeLinkDialog } from "@/components/staff/resume-link-dialog";
import { PermissionGate } from "@/components/staff/pipeline/actions";
import { errMessage } from "@/components/staff/pipeline/hooks";
import { RETRYABLE_CHECKS, RetryDialog, type DisplayStep } from "@/components/staff/verification-checks";
import { isRetryStillRunning, retryGuardKey } from "@/lib/staff/verification-dashboard";
import { CrifAnalysisSection } from "@/components/staff/customer-360/crif-analysis-panel";
import type { TabCtx } from "@/components/staff/customer-360/types";
import {
  AadhaarBody, AadhaarPanLinkBody, AddressCheckBody, BureauBody, EmailBody, EsignBody, PanBody, PennyBody, SelfieBody, UanBody,
  checkedAt, statusLabel, statusVariant,
} from "@/components/staff/customer-360/third-party/presenters";
import { skipTraceApi, staffApi, type StepResult } from "@/lib/api/applications";
import { AADHAAR_PAN_LINK, withAadhaarPanLink } from "@/lib/staff/aadhaar-pan-link";
import { bankAnalysisApi } from "@/lib/api/bank-analysis";
import { paiseToINR } from "@/lib/api/applications";
import { formatDate } from "@/lib/utils";

// Credentials only: staff see identifiers (account, UAN) in the raw view, never one-time links/tokens.
const REDACT = new Set(["url", "token", "videoUrl", "sessionId"]);
const rawJson = (d: Record<string, unknown>) =>
  JSON.stringify(d, (k, v) => (REDACT.has(k) ? "[hidden]" : v), 2);

type Row = {
  key: string;
  title: string;
  checkType?: string;
  body?: (s: StepResult, aadhaar?: string | null) => React.ReactNode;
  /** The check an ADMIN re-run calls (the backend retry allow-list), when it is not `checkType`. */
  runCheck?: string;
  /** A borrower-only step: staff cannot run it, only send the borrower a link to finish it. */
  sendLink?: boolean;
};

/** The check type an ADMIN "Run" on this row re-runs, or null when staff cannot run it. */
const runCheckOf = (r: Row): string | null => {
  const c = r.runCheck ?? r.checkType;
  return c && RETRYABLE_CHECKS.includes(c) ? c : null;
};

const ROWS: Row[] = [
  { key: "pan", title: "PAN Detail", checkType: "PAN", body: (s, aadhaar) => <PanBody step={s} aadhaar={aadhaar} /> },
  { key: "aadhaar-pan", title: "Aadhaar–PAN Linkage", checkType: AADHAAR_PAN_LINK, runCheck: "PAN", body: (s) => <AadhaarPanLinkBody step={s} /> },
  { key: "uan", title: "UAN / EPFO Detail", checkType: "EMPLOYMENT", body: (s) => <UanBody step={s} /> },
  { key: "aadhaar", title: "Aadhaar Detail (DigiLocker)", checkType: "AADHAAR", sendLink: true, body: (s, aadhaar) => <AadhaarBody step={s} aadhaar={aadhaar} /> },
  { key: "email", title: "Email Verification", checkType: "EMAIL", body: (s) => <EmailBody step={s} /> },
  // The analysis is read off the bureau report until the bank-statement analyser is live.
  { key: "bank", title: "Bank Statement Analysis", runCheck: "BUREAU" },
  {
    key: "bureau",
    title: "Bureau and Credit Report",
    checkType: "BUREAU",
    body: (s) => (
      <div className="space-y-3">
        <CreditScoreGauge score={s.score ?? null} size="sm" />
        <BureauBody step={s} />
      </div>
    ),
  },
  { key: "penny", title: "Penny Drop", checkType: "PENNY_DROP", body: (s) => <PennyBody step={s} /> },
  { key: "selfie", title: "Selfie / Liveness", checkType: "SELFIE", body: (s) => <SelfieBody step={s} /> },
  { key: "address", title: "Address Check", checkType: "ADDRESS", body: (s) => <AddressCheckBody step={s} /> },
  { key: "esign", title: "eSign", checkType: "ESIGN", sendLink: true, body: (s) => <EsignBody step={s} /> },
  { key: "skip", title: "Skip Trace" },
];

function Header({ title, open, pill, provider, when }: {
  title: string; open: boolean; pill: React.ReactNode; provider?: string | null; when?: string | null;
}) {
  const Chevron = open ? ChevronDown : ChevronRight;
  return (
    <div className="flex items-center gap-2 text-xs">
      <Chevron size={14} className="shrink-0 text-black" />
      <span className="font-semibold text-black">{title}</span>
      {pill}
      {provider && <Badge variant="neutral" size="sm">{provider}</Badge>}
      {when && <span className="ml-auto text-[10px] text-black">{when}</span>}
    </div>
  );
}

export function ThirdPartyTab({ detail, customerId, applicationId, app }: TabCtx) {
  const aadhaar = detail.profile?.aadhaar ?? null;
  const verQ = useQuery({
    queryKey: ["verifications", applicationId],
    queryFn: () => staffApi.verifications(applicationId as number),
    enabled: applicationId != null,
  });
  const bankQ = useQuery({ queryKey: ["bank-analysis", customerId], queryFn: () => bankAnalysisApi.get(customerId) });
  const steps = React.useMemo(() => withAadhaarPanLink(verQ.data ?? [], aadhaar), [verQ.data, aadhaar]);
  const stepOf = (r: Row) => (r.checkType ? steps.find((x) => x.checkType === r.checkType) : undefined);
  const hasData = (r: Row) => (r.key === "bank" ? bankQ.data != null : r.key === "skip" ? false : stepOf(r) != null);
  const firstWithData = ROWS.find(hasData)?.key ?? null;
  const [open, setOpen] = React.useState<string | null | undefined>(undefined);
  const current = open === undefined ? firstWithData : open;

  // ADMIN re-runs. Same billable-call guard as the Verifications tab: once a retry's outcome is
  // unknown (the client gave up at 120s), Run stays off for that check until its row is rewritten.
  const qc = useQueryClient();
  const [retry, setRetry] = React.useState<DisplayStep | null>(null);
  const [inFlight, setInFlight] = React.useState<Record<string, string | null>>({});
  const release = (key: string) =>
    setInFlight((prev) => {
      if (!(key in prev)) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
  const stepByType = (c: string) => steps.find((x) => x.checkType === c);
  const running = (c: string) =>
    applicationId != null && isRetryStillRunning(inFlight, retryGuardKey(applicationId, c), stepByType(c)?.checkedAt);
  const openRetry = (c: string) =>
    setRetry(stepByType(c) ?? { checkType: c, status: "NOT_RUN", message: null, derived: {} } as DisplayStep);

  // DigiLocker and eSign are the borrower's own acts: the backend issues a resume link only on a
  // SANCTIONED file (the offer journey) whose step is not already done.
  const [linkFor, setLinkFor] = React.useState<string | null>(null);
  const linkBlocked = (c: string): string | null =>
    stepByType(c)?.status === "PASS" ? "Already completed by the borrower"
    : app?.status !== "SANCTIONED" ? "Available once the file is sanctioned (the borrower's offer journey)"
    : null;

  const [confirmSkip, setConfirmSkip] = React.useState(false);
  const skip = useMutation({
    mutationFn: () => skipTraceApi.run(customerId),
    onSuccess: (run) => {
      setConfirmSkip(false);
      setOpen("skip");
      toast.success(run.status === "SUCCESS" ? "Skip trace complete." : run.status === "NO_RECORD" ? "Digitap has no record for this customer." : "Skip trace recorded.");
      void qc.invalidateQueries({ queryKey: ["skip-trace", customerId] });
    },
    onError: (e) => {
      setConfirmSkip(false);
      toast.error(errMessage(e));
      // A failed attempt is still stored server-side.
      void qc.invalidateQueries({ queryKey: ["skip-trace", customerId] });
    },
  });

  if (applicationId == null) {
    return <EmptyState title="No application yet" hint="Third-party results appear once the customer has an application." />;
  }
  if (verQ.isLoading) return <Skeleton variant="line" rows={6} />;
  if (verQ.error) return <ErrorState error={verQ.error} onRetry={() => void verQ.refetch()} />;

  return (
    <div className="space-y-2">
      {ROWS.map((r) => {
        const s = stepOf(r);
        const isOpen = current === r.key;
        const bank = r.key === "bank" ? bankQ.data : null;
        const pill =
          r.key === "skip" ? null
          : bank ? <Badge variant={bank.status === "ANALYSED" ? "success" : bank.status === "FAILED" ? "error" : "neutral"} size="sm">{bank.status === "ANALYSED" ? "Analysed" : bank.status === "FAILED" ? "Failed" : "Pending"}</Badge>
          : r.key === "bank" ? <Badge variant="success" size="sm">Analysed</Badge>
          : s ? <Badge variant={statusVariant(s)} size="sm">{r.key === "aadhaar-pan" && s.status === "PASS" ? "Success" : statusLabel(s)}</Badge>
          : <Badge variant="neutral" size="sm">Not run</Badge>;
        return (
          <div key={r.key} className="rounded border border-line bg-white">
            <div className="flex items-center gap-2 pr-2">
              <button
                type="button"
                aria-expanded={isOpen}
                className="min-w-0 flex-1 px-3 py-2 text-left"
                onClick={() => setOpen(isOpen ? null : r.key)}
              >
                <Header title={r.title} open={isOpen} pill={pill} provider={r.key === "bank" && !bank ? "CRIF" : s?.provider} when={s ? checkedAt(s) : null} />
              </button>
              <PermissionGate permission="verification:retry">
                {r.sendLink && r.checkType ? (
                  <SendLinkButton title={r.title} blocked={linkBlocked(r.checkType)} onClick={() => setLinkFor(r.checkType!)} />
                ) : (
                <RunButton
                  row={r}
                  ran={(() => { const c = runCheckOf(r); return c ? stepByType(c) != null : false; })()}
                  busy={r.key === "skip" ? skip.isPending : (() => { const c = runCheckOf(r); return c ? running(c) : false; })()}
                  onRun={() => {
                    if (r.key === "skip") setConfirmSkip(true);
                    else { const c = runCheckOf(r); if (c) openRetry(c); }
                  }}
                />
                )}
              </PermissionGate>
            </div>
            {isOpen && (
              <div className="space-y-3 border-t border-line p-3">
                {r.key === "skip" ? (
                  <SkipTracePanel customerId={customerId} />
                ) : r.key === "bank" ? (
                  <>
                    {bank && <BankBody bank={bank} />}
                    <CrifAnalysisSection detail={detail} applicationId={applicationId} showRaw={!bank} />
                  </>
                ) : !s ? (
                  <EmptyState title={`${r.title} not run`} className="py-4" />
                ) : (
                  <>
                    {s.message && <p className="text-[10.4px] text-black">{s.message}</p>}
                    {r.body?.(s, aadhaar)}
                    {r.key === "bureau" && <CreditProfileCard applicationId={applicationId} />}
                    <details>
                      <summary className="cursor-pointer text-[10px] text-black">View raw response</summary>
                      <pre className="mt-1 max-h-48 overflow-auto rounded bg-neutral-50 p-2 font-mono text-[10px] text-black">{rawJson(s.derived)}</pre>
                    </details>
                  </>
                )}
              </div>
            )}
          </div>
        );
      })}

      {retry && (
        <RetryDialog
          applicationId={applicationId}
          step={retry}
          currentCheckedAt={stepByType(retry.checkType)?.checkedAt ?? null}
          stillRunning={running(retry.checkType)}
          onSent={(baseline) => setInFlight((prev) => ({ ...prev, [retryGuardKey(applicationId, retry.checkType)]: baseline }))}
          onAnswered={() => release(retryGuardKey(applicationId, retry.checkType))}
          onRefresh={() => void verQ.refetch()}
          onClose={() => setRetry(null)}
        />
      )}
      {linkFor && <ResumeLinkDialog applicationId={applicationId} checkType={linkFor} onClose={() => setLinkFor(null)} />}
      <ConfirmDialog
        open={confirmSkip}
        onClose={() => setConfirmSkip(false)}
        onConfirm={() => skip.mutate()}
        busy={skip.isPending}
        title="Run skip trace?"
        confirmLabel="Run skip trace"
        body={<p className="m-0">Looks up alternate mobiles, emails and addresses from Digitap for this customer. Each run is a paid lookup.</p>}
      />
    </div>
  );
}

const ROW_ACTION =
  "inline-flex shrink-0 items-center gap-1 rounded border border-line px-1.5 py-0.5 text-[10px] font-semibold text-navy hover:bg-grey-100 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent";

/** Borrower-only steps (DigiLocker, eSign): send the borrower a link instead of a Run. */
function SendLinkButton({ title, blocked, onClick }: { title: string; blocked: string | null; onClick: () => void }) {
  return (
    <button type="button" className={ROW_ACTION} onClick={onClick} disabled={blocked != null}
      title={blocked ?? "Send the borrower a link to finish this step"} aria-label={`Send link for ${title}`}>
      <Link2 size={11} /> Send link
    </button>
  );
}

/** The compact per-row ADMIN action: Run / Re-run, or a disabled button saying why not. */
function RunButton({ row, ran, busy, onRun }: { row: Row; ran: boolean; busy: boolean; onRun: () => void }) {
  const cls = ROW_ACTION;
  const runnable = row.key === "skip" || runCheckOf(row) != null;
  if (!runnable) {
    return (
      <button type="button" className={cls} disabled title="This check cannot be run by staff." aria-label={`Run ${row.title} (unavailable)`}>
        <RotateCcw size={11} /> Run
      </button>
    );
  }
  const Icon = row.key === "skip" ? Radar : RotateCcw;
  const label = row.key === "skip" ? "Run" : ran ? "Re-run" : "Run";
  const hint = row.key === "bank" ? "Re-pulls the bureau report this analysis is read from" : row.key === "aadhaar-pan" ? "Re-runs the PAN check, which returns the linkage" : undefined;
  return (
    <button type="button" className={cls} onClick={onRun} disabled={busy} title={hint} aria-label={`${label} ${row.title}`}>
      {busy ? <Loader2 size={11} className="animate-spin" /> : <Icon size={11} />} {label}
    </button>
  );
}

function BankBody({ bank }: { bank: NonNullable<Awaited<ReturnType<typeof bankAnalysisApi.get>>> }) {
  return (
    <div className="space-y-2 text-[10.4px] text-black">
      <div>
        {bank.bank}
        {bank.salary && <> · Salary {bank.salary.verdict} · min {paiseToINR(bank.salary.minSalaryPaise)} · analysed {formatDate(bank.salary.analysedAt)}</>}
      </div>
      {bank.accounts.map((a) => (
        <div key={a.maskedNumber} className="font-mono">{a.maskedNumber} · {a.type} · {a.txnCount} txns</div>
      ))}
    </div>
  );
}
