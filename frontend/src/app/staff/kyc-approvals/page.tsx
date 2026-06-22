"use client";

import * as React from "react";
import { ShieldCheck, Check, X, Lock } from "lucide-react";
import { Button } from "@/components/ui";
import { InfoRow } from "@/components/borrower/summary";
import { PageHeader, KycStatusBadge } from "@/components/staff/staff-ui";
import { useMockDb } from "@/lib/mock/store";
import { useStaffSession } from "@/lib/mock/session";
import { hasPermission } from "@/lib/auth/rbac";
import { useMounted } from "@/hooks/use-mounted";
import { formatINR0 } from "@/lib/utils";

const CHECKS: Array<{ key: "pan" | "aadhaar" | "selfie" | "address" | "bank"; label: string }> = [
  { key: "pan", label: "PAN" },
  { key: "aadhaar", label: "Aadhaar" },
  { key: "selfie", label: "Selfie" },
  { key: "address", label: "Address" },
  { key: "bank", label: "Bank" },
];

export default function KycApprovalsPage() {
  const mounted = useMounted();
  const { session } = useStaffSession();
  const applications = useMockDb((s) => s.applications);
  const decideKyc = useMockDb((s) => s.decideKyc);

  if (!mounted || !session) return <div className="h-64 rounded border border-line bg-white" />;

  const pending = applications.filter((a) => a.stage === "KYC_REVIEW");
  const canAct = hasPermission(session.role, "kyc:approve");
  const actor = { id: session.userId, name: session.name, role: session.role };

  return (
    <div>
      <PageHeader title="KYC approvals" subtitle="Clear or reject identity verification before credit review.">
        <span className="rounded-full bg-navy-tint px-3 py-1 text-sm font-semibold text-navy">{pending.length} pending</span>
      </PageHeader>

      {!canAct && (
        <div className="mb-5 flex items-start gap-2 rounded border border-warning-100 bg-warning-50 p-4 text-sm text-warning-800">
          <Lock size={16} className="mt-0.5 flex-shrink-0" /> You&apos;re viewing as {session.name}. Only a KYC Approver can clear identity checks.
        </div>
      )}

      {pending.length === 0 ? (
        <div className="rounded border border-line bg-white p-10 text-center text-muted">No applications awaiting KYC clearance.</div>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {pending.map((a) => (
            <div key={a.id} className="rounded border border-line bg-white p-5 shadow-sm">
              <div className="mb-3 flex items-start justify-between gap-3">
                <div>
                  <div className="font-serif text-lg font-semibold text-navy">{a.applicantName}</div>
                  <div className="text-xs text-muted">{a.id} · {a.employer}</div>
                </div>
                <span className="flex items-center gap-1.5 text-xs font-semibold text-gold-dark"><ShieldCheck size={14} /> {a.kyc.overall.replace("_", " ")}</span>
              </div>

              <div className="mb-3 flex flex-wrap gap-2">
                {CHECKS.map(({ key, label }) => (
                  <span key={key} className="inline-flex items-center gap-1.5 rounded border border-line px-2.5 py-1 text-xs">
                    {label} <KycStatusBadge status={a.kyc[key]} />
                  </span>
                ))}
              </div>

              <InfoRow label="Requested" value={formatINR0(a.requestedAmount)} />
              <InfoRow label="PAN" value={a.panMasked} />

              <div className="mt-4 flex gap-2">
                <Button variant="success" size="sm" disabled={!canAct} leftIcon={<Check size={15} />} onClick={() => decideKyc(a.id, true, actor)}>
                  Clear KYC
                </Button>
                <Button variant="destructive" size="sm" disabled={!canAct} leftIcon={<X size={15} />} onClick={() => decideKyc(a.id, false, actor)}>
                  Reject
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
