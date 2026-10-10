"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, Inbox, RefreshCw } from "lucide-react";
import { InfoTooltip, Skeleton } from "@/components/ui";
import {
  collectionsApi,
  featureFlagsApi,
  staffApi,
  staffReferralApi,
  type ApplicationView,
  type FeatureFlags,
} from "@/lib/api/applications";
import type { StaffRole } from "@/lib/auth/rbac";
import { TONE_SOLID, TONE_TEXT } from "./colors";
import { useMounted } from "@/hooks/use-mounted";

const REFRESH_MS = 10_000; // small, actionable queues
const SLOW_MS = 60_000; // whole-book lists

/** Per-role "your queue" label (+ an ⓘ explanation) and the live statuses that feed it. */
const QUEUE: Partial<Record<StaffRole, { label: string; info: string }>> = {
  CREDIT_EXECUTIVE: {
    label: "Leads to decide",
    info: "Verify the file, then accept it with a sanctioned amount and repayment date, reject it, or park it as pending. Your decision is final — it goes straight to disbursement.",
  },
  CREDIT_HEAD: {
    label: "Leads to assign",
    info: "Hand each submitted intake to an active credit executive, or assign it to yourself. To decide a file, switch to Credit Executive.",
  },
  DISBURSEMENT_HEAD: {
    label: "Approved loans to release",
    info: "Release funds to the borrower's bank, then enter the transaction id — that activates the loan immediately. The transaction id is required; there is no second pair of eyes after you.",
  },
  ACCOUNTANT: {
    label: "Repayments to verify",
    info: "Confirm borrower repayments landed, and validate the payments collections raise. See all money movement under Accounting → all transactions.",
  },
  COLLECTION_HEAD: {
    label: "Settlements awaiting your approval",
    info: "Approve or reject the settlements collection executives propose. Separation of duties applies — you can't approve one you proposed. Work overdue loans from the collections desk.",
  },
  COLLECTION_EXECUTIVE: {
    label: "Open collection cases",
    info: "Work overdue loans assigned to you in your DPD buckets and log borrower interactions.",
  },
};

/**
 * Deep-link from a role to the page where it acts on its queue.
 *
 * Every role now points at /staff/applications — the single workbench that renders each role's own
 * queues. The Head's settlements worklist is still its own page, reached from the nav.
 */
const ROLE_HREF: Partial<Record<StaffRole, string>> = {
  CREDIT_EXECUTIVE: "/staff/applications",
  CREDIT_HEAD: "/staff/applications",
  DISBURSEMENT_HEAD: "/staff/applications",
  ACCOUNTANT: "/staff/applications",
  COLLECTION_HEAD: "/staff/applications",
  COLLECTION_EXECUTIVE: "/staff/applications",
};

/** A non-application actionable source (repayments, referral payouts, settlements, cases). */
type QueueExtra = { key: string; label: string; count: number; href: string };
/**
 * A role's full action queue: applications the role acts on + non-application actionable sources,
 * plus whether any source it read FAILED. The per-source `.catch`es below are deliberate — one
 * dead call must never zero the whole count — but swallowing them silently made a dead backend
 * render as "you're all caught up", the one wrong answer this page must not give. The flag is how
 * the page tells "genuinely empty" from "could not load".
 */
type RoleQueue = { apps: ApplicationView[]; extras: QueueExtra[]; failed: boolean };

/** One fetched source: what it returned, and whether the call failed (vs. came back empty). */
type Source<T> = { value: T; failed: boolean };

const safe = (p: Promise<ApplicationView[]>): Promise<Source<ApplicationView[]>> =>
  p
    .then((value) => ({ value, failed: false }))
    .catch(() => ({ value: [] as ApplicationView[], failed: true }));
const countOf = <T,>(p: Promise<T[]>): Promise<Source<number>> =>
  p.then((r) => ({ value: r.length, failed: false })).catch(() => ({ value: 0, failed: true }));

/** Mirrors the accountant's repayment-verify queue on /staff/applications. */
const pendingRepaymentCount = () => countOf(staffApi.pendingRepayments());

/** Mirrors the accountant's collections-payment validation queue on /staff/applications. */
const pendingCollectionPaymentCount = () =>
  countOf(collectionsApi.listPayments("PENDING_ACCOUNTANT"));

const repaymentsExtra = (count: number): QueueExtra =>
  ({ key: "repayments", label: "Repayments to verify", count, href: "/staff/applications" });
const collectionPaymentsExtra = (count: number): QueueExtra =>
  ({ key: "collection-payments", label: "Collections payments to validate", count, href: "/staff/applications" });
const settlementsExtra = (count: number): QueueExtra =>
  ({ key: "settlements", label: "Settlements to approve", count, href: "/staff/collections/settlements" });
const referralPayoutsExtra = (count: number): QueueExtra =>
  ({ key: "referral-payouts", label: "Referral payouts to settle", count, href: "/staff/disbursement/referrals" });

/**
 * Whether to count pending referral payouts (Disbursement Head's queue): the `referral` flag is a
 * kill switch (only an explicit `false` turns it off), a failed flag read fails open, and `undefined`
 * means "not known yet" so the caller waits rather than asking for a feature that may be off.
 */
function referralPayoutsGate(flags: FeatureFlags | undefined, flagsFailed: boolean): boolean | undefined {
  if (flags) return flags.referral !== false;
  return flagsFailed ? true : undefined;
}

/**
 * The live items for a role's action queue — the union of everything the role's queue
 * page(s) actually list. Every source is individually fault-tolerant (`.catch`) so one
 * failing call can never zero the whole count.
 */
async function fetchRoleQueue(role: StaffRole): Promise<RoleQueue> {
  let base: RoleQueue;
  switch (role) {
    case "CREDIT_EXECUTIVE": {
      const own = await safe(staffApi.listByStatus("CREDIT_EXEC_PENDING"));
      base = { apps: own.value, extras: [], failed: own.failed };
      break;
    }
    case "CREDIT_HEAD": {
      // Intakes waiting to be assigned. Deciding is the Executive's job (switch role).
      const queue = await safe(staffApi.creditQueue());
      base = { apps: queue.value, extras: [], failed: queue.failed };
      break;
    }
    case "DISBURSEMENT_HEAD": {
      // Pending referral payouts are counted by their own query in the component (referralPayoutsQuery),
      // gated on the shell's cached feature flags — so they no longer wait here on a flags read.
      const [pending, failedTransfers] = await Promise.all([
        safe(staffApi.listByStatus("DISBURSEMENT_PENDING")),
        safe(staffApi.listByStatus("DISBURSEMENT_FAILED")),
      ]);
      base = {
        apps: [...pending.value, ...failedTransfers.value],
        extras: [],
        failed: pending.failed || failedTransfers.failed,
      };
      break;
    }
    case "ACCOUNTANT": {
      // No application queue: since V48 the Accountant has no disbursement step at all. Their work
      // is money coming back in — borrower repayments and what collections took in the field.
      const [repayments, collected] = await Promise.all([
        pendingRepaymentCount(),
        pendingCollectionPaymentCount(),
      ]);
      const extras: QueueExtra[] = [];
      if (repayments.value > 0) extras.push(repaymentsExtra(repayments.value));
      if (collected.value > 0) extras.push(collectionPaymentsExtra(collected.value));
      base = { apps: [], extras, failed: repayments.failed || collected.failed };
      break;
    }
    case "COLLECTION_HEAD":
      // Settlements count comes from settlementsQuery alone — no separate fetch.
      base = { apps: [], extras: [], failed: false };
      break;
    case "COLLECTION_EXECUTIVE":
      // "Your open collection cases" derives from casesQuery alone — no separate fetch.
      base = { apps: [], extras: [], failed: false };
      break;
    case "TELECALLER":
    case "DSA":
    default:
      base = { apps: [], extras: [], failed: false };
      break;
  }

  return base;
}

/**
 * Compact "your work" strip at the top of the dashboard: the working role's queue label (with its
 * ⓘ explanation), how many items need action and an Open link. Roles with no queue (Admin,
 * Telecaller) render nothing.
 */
export function QueueStrip({ role, staffId }: { role: StaffRole; staffId?: string }) {
  const mounted = useMounted();
  const queryClient = useQueryClient();
  const sid = staffId != null ? Number(staffId) : undefined;
  const queue = QUEUE[role];

  const queueQuery = useQuery({
    queryKey: ["staff-dashboard-queue", role, staffId],
    queryFn: () => fetchRoleQueue(role),
    enabled: mounted && !!queue,
    refetchInterval: REFRESH_MS,
  });

  // The staff shell's own cache entry (same key, queryFn and staleTime) so this dedupes onto it.
  const flagsQuery = useQuery({
    queryKey: ["feature-flags"],
    queryFn: () => featureFlagsApi.get(),
    enabled: mounted && role === "DISBURSEMENT_HEAD",
    staleTime: 60_000,
    refetchInterval: SLOW_MS,
  });
  const referralOn = referralPayoutsGate(flagsQuery.data, flagsQuery.isError);

  const referralPayoutsQuery = useQuery({
    queryKey: ["staff-dashboard-queue", "referral-payouts", staffId],
    queryFn: () => countOf(staffReferralApi.payouts("PENDING")),
    enabled: mounted && role === "DISBURSEMENT_HEAD" && referralOn === true,
    refetchInterval: REFRESH_MS,
  });

  const casesQuery = useQuery({
    queryKey: ["staff-dashboard-cases"],
    queryFn: () => collectionsApi.listCases(),
    enabled: mounted && role === "COLLECTION_EXECUTIVE",
    refetchInterval: SLOW_MS,
    staleTime: SLOW_MS,
  });
  const settlementsQuery = useQuery({
    queryKey: ["staff-dashboard-settlements"],
    queryFn: () => collectionsApi.listSettlements(),
    enabled: mounted && role === "COLLECTION_HEAD",
    refetchInterval: SLOW_MS,
    staleTime: SLOW_MS,
  });

  if (!queue) return null;

  const queueData: RoleQueue = queueQuery.data ?? { apps: [], extras: [], failed: false };
  const pendingSettlements = (settlementsQuery.data ?? []).filter((s) => s.status === "PROPOSED").length;
  const settlementExtras: QueueExtra[] =
    role === "COLLECTION_HEAD" && pendingSettlements > 0 ? [settlementsExtra(pendingSettlements)] : [];
  // Fail CLOSED without a resolvable staff id — showing every company case is the bug.
  const myCases =
    role !== "COLLECTION_EXECUTIVE" || sid == null
      ? []
      : (casesQuery.data ?? []).filter((c) => c.assignedOfficerId === sid);
  const caseExtras: QueueExtra[] =
    myCases.length > 0
      ? [{ key: "cases", label: "Your open collection cases", count: myCases.length, href: "/staff/applications" }]
      : [];
  const countsReferralPayouts = role === "DISBURSEMENT_HEAD" && referralOn === true;
  const pendingPayouts = countsReferralPayouts ? (referralPayoutsQuery.data?.value ?? 0) : 0;
  const payoutExtras: QueueExtra[] = pendingPayouts > 0 ? [referralPayoutsExtra(pendingPayouts)] : [];

  const extras = [...queueData.extras, ...payoutExtras, ...settlementExtras, ...caseExtras].filter((e) => e.count > 0);
  const headline = queueData.apps.length + extras.reduce((s, e) => s + e.count, 0);

  const loading =
    queueQuery.isLoading ||
    (role === "COLLECTION_HEAD" && settlementsQuery.isLoading) ||
    (role === "COLLECTION_EXECUTIVE" && casesQuery.isLoading) ||
    (role === "DISBURSEMENT_HEAD" && (referralOn === undefined || (referralOn && referralPayoutsQuery.isLoading)));
  const failed =
    queueQuery.isError ||
    queueData.failed ||
    (role === "COLLECTION_HEAD" && settlementsQuery.isError) ||
    (role === "COLLECTION_EXECUTIVE" && casesQuery.isError) ||
    (countsReferralPayouts && (referralPayoutsQuery.isError || referralPayoutsQuery.data?.failed === true));

  const href = ROLE_HREF[role];
  const refresh = () =>
    void queryClient.invalidateQueries({ predicate: (q) => String(q.queryKey[0]).startsWith("staff-dashboard-") });

  return (
    <section
      aria-label="Your work"
      className="surface mb-4 flex flex-wrap items-center gap-3 px-4 py-3"
    >
      <span
        aria-hidden
        className="grid h-9 w-9 shrink-0 place-items-center rounded-full border border-line bg-paper shadow-pill"
        style={{ color: TONE_TEXT.emerald }}
      >
        <Inbox size={15} />
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-medium text-ink">{queue.label}</span>
          <InfoTooltip content={queue.info} />
        </div>
        {extras.length > 0 && (
          <p className="m-0 text-xs text-muted">
            {extras.map((e, i) => (
              <React.Fragment key={e.key}>
                {i > 0 && " · "}
                <Link href={e.href} className="hover:underline">
                  {e.count} {e.label.toLowerCase()}
                </Link>
              </React.Fragment>
            ))}
          </p>
        )}
      </div>
      {loading ? (
        <Skeleton rows={1} className="h-7 w-24" />
      ) : failed && headline === 0 ? (
        <button type="button" onClick={refresh} className="flex items-center gap-1.5 text-xs font-semibold text-error-700">
          <RefreshCw size={13} /> Couldn&apos;t load your queue — Refresh
        </button>
      ) : (
        <span className="flex items-center gap-1.5 rounded-full bg-grey-100 px-3 py-1 text-xs font-medium text-ink">
          <span aria-hidden className="h-2 w-2 rounded-full" style={{ background: TONE_SOLID.emerald }} />
          <b className="font-semibold tabular-nums">{headline}</b> {headline === 1 ? "item needs" : "items need"} your action
        </span>
      )}
      {href && (
        <Link href={href} className="btn btn-sm btn-navy">
          Open queue <ArrowRight size={15} />
        </Link>
      )}
    </section>
  );
}
