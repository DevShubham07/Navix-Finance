"use client";

import * as React from "react";
import Link from "next/link";
import { ArrowRight, ShieldCheck, ClipboardList, Banknote, Receipt, Wallet, PhoneCall } from "lucide-react";
import { PageHeader, StatCard, StageBadge } from "@/components/staff/staff-ui";
import { DpdBadge } from "@/components/staff/dpd-badge";
import { useMockDb } from "@/lib/mock/store";
import { useStaffSession, STAFF_ROLE_LABELS } from "@/lib/mock/session";
import type { StaffRole } from "@/lib/auth/rbac";
import type { AppStage, ApplicationRecord } from "@/lib/mock/types";
import { useMounted } from "@/hooks/use-mounted";
import { formatINR0 } from "@/lib/utils";

const QUEUE: Partial<Record<StaffRole, { stages: AppStage[]; label: string }>> = {
  KYC_APPROVER: { stages: ["KYC_REVIEW"], label: "Applications awaiting KYC clearance" },
  CREDIT_EXECUTIVE: { stages: ["CREDIT_QUEUE", "CREDIT_REVIEW"], label: "Applications to review" },
  CREDIT_HEAD: { stages: ["CREDIT_QUEUE", "CREDIT_DECISION"], label: "Decisions awaiting your approval" },
  DISBURSEMENT_HEAD: { stages: ["DISBURSEMENT"], label: "Approved loans to release" },
  ACCOUNTANT: { stages: ["ACCOUNTING"], label: "Transfers to confirm" },
};

function hrefForStage(app: ApplicationRecord): string {
  switch (app.stage) {
    case "KYC_REVIEW": return "/staff/kyc-approvals";
    case "CREDIT_QUEUE":
    case "CREDIT_REVIEW":
    case "CREDIT_DECISION": return `/staff/credit/${app.id}`;
    case "DISBURSEMENT": return "/staff/disbursement";
    case "ACCOUNTING": return "/staff/accounting";
    default: return `/staff/credit/${app.id}`;
  }
}

export default function StaffDashboardPage() {
  const mounted = useMounted();
  const { session } = useStaffSession();
  const apps = useMockDb((s) => s.applications);
  const collections = useMockDb((s) => s.collections);

  if (!mounted || !session) {
    return <div className="h-64 rounded border border-line bg-white" />;
  }

  const count = (st: AppStage) => apps.filter((a) => a.stage === st).length;
  const inCredit = count("CREDIT_QUEUE") + count("CREDIT_REVIEW") + count("CREDIT_DECISION");
  const queue = QUEUE[session.role];
  const myItems = queue ? apps.filter((a) => queue.stages.includes(a.stage)) : [];
  const showCollections = session.role === "COLLECTIONS_HEAD" || session.role === "COLLECTION_OFFICER" || session.role === "ADMIN";

  return (
    <div>
      <PageHeader title={`Welcome, ${session.name.split(" ")[0]}`} subtitle={`${STAFF_ROLE_LABELS[session.role]} · operations overview`} />

      <div className="mb-8 grid grid-cols-2 gap-4 lg:grid-cols-3 xl:grid-cols-6">
        <StatCard label="KYC review" value={count("KYC_REVIEW")} accent="gold" />
        <StatCard label="In credit" value={inCredit} />
        <StatCard label="To release" value={count("DISBURSEMENT")} />
        <StatCard label="To confirm" value={count("ACCOUNTING")} />
        <StatCard label="Active loans" value={count("ACTIVE")} accent="success" />
        <StatCard label="In collections" value={collections.length} accent="error" />
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="mb-0 text-xl">{queue ? queue.label : "Pipeline"}</h2>
            {queue ? <span className="rounded-full bg-navy-tint px-3 py-1 text-sm font-semibold text-navy">{myItems.length} pending</span> : null}
          </div>

          {queue ? (
            myItems.length ? (
              <ul className="divide-y divide-grey-200 rounded border border-line bg-white">
                {myItems.map((a) => (
                  <li key={a.id}>
                    <Link href={hrefForStage(a)} className="flex items-center gap-4 px-4 py-3 transition hover:bg-grey-100">
                      <span className="grid h-10 w-10 flex-shrink-0 place-items-center rounded-full bg-navy-tint font-serif text-sm font-bold text-navy">
                        {a.applicantName.split(" ").map((n) => n[0]).join("").slice(0, 2)}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-semibold text-ink">{a.applicantName}</span>
                        <span className="block text-xs text-muted">{a.id} · {formatINR0(a.requestedAmount)} · risk {a.riskCategory}</span>
                      </span>
                      <StageBadge stage={a.stage} />
                      <ArrowRight size={16} className="flex-shrink-0 text-muted" />
                    </Link>
                  </li>
                ))}
              </ul>
            ) : (
              <div className="rounded border border-line bg-white p-8 text-center text-sm text-muted">
                You&apos;re all caught up — nothing in your queue.
              </div>
            )
          ) : (
            <div className="rounded border border-line bg-white p-6 text-sm text-muted">
              Use the navigation to open the queues your role can act on.
            </div>
          )}
        </div>

        <aside className="space-y-4">
          <div className="rounded border border-line bg-white p-5 shadow-sm">
            <h3 className="mb-2 font-serif text-base text-navy">Quick links</h3>
            <ul className="text-sm">
              <li><Link href="/staff/kyc-approvals" className="-mx-2 flex items-center gap-2 rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy"><ShieldCheck size={15} /> KYC approvals</Link></li>
              <li><Link href="/staff/credit/queue" className="-mx-2 flex items-center gap-2 rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy"><ClipboardList size={15} /> Credit queue</Link></li>
              <li><Link href="/staff/disbursement" className="-mx-2 flex items-center gap-2 rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy"><Banknote size={15} /> Disbursement</Link></li>
              <li><Link href="/staff/accounting" className="-mx-2 flex items-center gap-2 rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy"><Receipt size={15} /> Accounting</Link></li>
              <li><Link href="/staff/collections/buckets" className="-mx-2 flex items-center gap-2 rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy"><PhoneCall size={15} /> Collections</Link></li>
            </ul>
          </div>

          {showCollections && (
            <div className="rounded border border-line bg-white p-5 shadow-sm">
              <h3 className="mb-3 flex items-center gap-2 font-serif text-base text-navy"><Wallet size={16} /> Collections</h3>
              <ul>
                {collections.map((c) => (
                  <li key={c.id} className="flex items-center justify-between gap-2 text-sm">
                    <Link href={`/staff/collections/${c.loanId}`} className="-mx-2 min-w-0 flex-1 truncate rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy">{c.applicantName}</Link>
                    <DpdBadge dpd={c.daysPastDue} />
                  </li>
                ))}
              </ul>
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}
