"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight } from "lucide-react";
import { Badge, EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { CreditProfileCard } from "@/components/staff/credit-profile-card";
import { CreditScoreGauge } from "@/components/staff/credit-score-gauge";
import { SkipTracePanel } from "@/components/staff/skip-trace-panel";
import { CrifAnalysisSection } from "@/components/staff/customer-360/crif-analysis-panel";
import type { TabCtx } from "@/components/staff/customer-360/types";
import {
  AadhaarBody, AadhaarPanLinkBody, AddressCheckBody, BureauBody, EmailBody, EsignBody, PanBody, PennyBody, SelfieBody, UanBody,
  checkedAt, statusLabel, statusVariant,
} from "@/components/staff/customer-360/third-party/presenters";
import { staffApi, type StepResult } from "@/lib/api/applications";
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
};

const ROWS: Row[] = [
  { key: "pan", title: "PAN Detail", checkType: "PAN", body: (s, aadhaar) => <PanBody step={s} aadhaar={aadhaar} /> },
  { key: "aadhaar-pan", title: "Aadhaar–PAN Linkage", checkType: AADHAAR_PAN_LINK, body: (s) => <AadhaarPanLinkBody step={s} /> },
  { key: "uan", title: "UAN / EPFO Detail", checkType: "EMPLOYMENT", body: (s) => <UanBody step={s} /> },
  { key: "aadhaar", title: "Aadhaar Detail (DigiLocker)", checkType: "AADHAAR", body: (s, aadhaar) => <AadhaarBody step={s} aadhaar={aadhaar} /> },
  { key: "email", title: "Email Verification", checkType: "EMAIL", body: (s) => <EmailBody step={s} /> },
  { key: "bank", title: "Bank Statement Analysis" },
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
  { key: "esign", title: "eSign", checkType: "ESIGN", body: (s) => <EsignBody step={s} /> },
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

export function ThirdPartyTab({ detail, customerId, applicationId }: TabCtx) {
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
            <button
              type="button"
              aria-expanded={isOpen}
              className="w-full px-3 py-2 text-left"
              onClick={() => setOpen(isOpen ? null : r.key)}
            >
              <Header title={r.title} open={isOpen} pill={pill} provider={r.key === "bank" && !bank ? "CRIF" : s?.provider} when={s ? checkedAt(s) : null} />
            </button>
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
    </div>
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
