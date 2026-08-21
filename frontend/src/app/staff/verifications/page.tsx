"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw, Search, X, ChevronRight } from "lucide-react";
import { Input } from "@/components/ui";
import { Dialog } from "@/components/ui/dialog";
import { PageHeader } from "@/components/staff/staff-ui";
import { PermissionGate, NoAccessNotice, errMessage } from "@/components/staff/live-pipeline";
import { VerificationChecksPanel } from "@/components/staff/verification-checks";
import { staffApi, type VerificationOverviewRow } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";

/** The four application-wise buckets, in triage priority order. */
type Bucket = "failures" | "awaiting" | "passed" | "notStarted";

const BUCKETS: { key: Bucket; label: string; accent: string }[] = [
  { key: "failures", label: "Has failures", accent: "text-error-700" },
  { key: "awaiting", label: "Awaiting borrower steps", accent: "text-warning-800" },
  { key: "passed", label: "All checks passed", accent: "text-success-700" },
  { key: "notStarted", label: "Not started", accent: "text-muted" },
];

/**
 * Application statuses that still need a KYC decision — the only ones this dashboard triages.
 * Rows for already-decided applications (KYC_APPROVED onward, rejected, closed…) are historical
 * evidence, not work, and would pollute the buckets forever.
 */
const UNDECIDED_STATUSES = ["DRAFT", "KYC_PENDING", "REVIEW_PENDING"];

/**
 * The checks a borrower must clear (PASS/REVIEW) before submit-kyc — mirrors backend
 * `ApplicationVerificationService.REQUIRED`. Needed so an application whose required checks were
 * never RUN (no row at all) isn't mistaken for "all checks passed" just because the few rows it
 * does have are green.
 */
const REQUIRED_CHECKS = ["PAN", "EMAIL", "ADDRESS", "AADHAAR", "BUREAU", "SALARY", "PENNY_DROP", "SELFIE"];

/**
 * Checks that inform the credit decision but gate nothing, and so must be kept out of the bucket
 * maths below.
 *
 * EMPLOYMENT is the EPFO/UAN lookup. It can never return PASS for a borrower the EPFO has no record
 * of — a first job, a cash employer, a non-PF establishment all land in REVIEW legitimately — and it
 * is deliberately absent from the backend's REQUIRED set for exactly that reason. Counting it in
 * `pendingReview` would drag a large share of otherwise-clean files out of "All checks passed" and
 * into "Awaiting borrower steps", where there is no borrower step to take. It gets its own chip on
 * the card instead: visible, not gating.
 */
const ADVISORY_CHECKS = ["EMPLOYMENT"];

/** One application rolled up from its verification rows (or a never-started KYC_PENDING app). */
interface AppCard {
  applicationId: number;
  customerId: number | null;
  borrowerName: string | null;
  borrowerMobile: string | null;
  total: number;
  passed: number;
  failed: number;
  pendingReview: number;
  /** EPFO/UAN outcome, shown as its own chip. Advisory — see {@link ADVISORY_CHECKS}. */
  employmentStatus: string | null;
  lastUpdate: string | null;
  bucket: Bucket;
}

/**
 * Verification dashboard: every application that needs a KYC decision, grouped **application-wise**
 * into triage buckets (has failures → awaiting borrower → all passed → not started). Each card opens
 * the shared {@link VerificationChecksPanel}, where a KYC approver can override a check with remarks.
 */
export default function VerificationsDashboardPage() {
  const [search, setSearch] = React.useState("");
  const [debounced, setDebounced] = React.useState("");
  const [selected, setSelected] = React.useState<AppCard | null>(null);
  React.useEffect(() => {
    const t = setTimeout(() => setDebounced(search.trim()), 300);
    return () => clearTimeout(t);
  }, [search]);

  const q = useQuery({
    queryKey: ["staff-verif-overview", debounced],
    queryFn: () => staffApi.verificationOverview({ q: debounced || undefined }),
    refetchInterval: 15_000,
  });
  // Enrich: KYC_PENDING applications with zero verification rows form the "Not started" bucket.
  const pendingQ = useQuery({
    queryKey: ["staff-verif-kyc-pending"],
    queryFn: () => staffApi.listByStatus("KYC_PENDING"),
    refetchInterval: 15_000,
  });

  const data = q.data;

  const cards = React.useMemo<AppCard[]>(() => {
    // Group verification rows by application — only applications still awaiting a KYC decision.
    // (applicationStatus is null on rows from before the field existed; treat those as undecided
    // rather than silently hiding them.)
    const rows = (data?.rows ?? []).filter(
      (r) => r.applicationStatus == null || UNDECIDED_STATUSES.includes(r.applicationStatus),
    );
    const byApp = new Map<number, VerificationOverviewRow[]>();
    for (const r of rows) {
      const list = byApp.get(r.applicationId) ?? [];
      list.push(r);
      byApp.set(r.applicationId, list);
    }
    const out: AppCard[] = [];
    for (const [applicationId, checks] of byApp) {
      const gating = checks.filter((c) => !ADVISORY_CHECKS.includes(c.checkType));
      const employment = checks.find((c) => c.checkType === "EMPLOYMENT") ?? null;
      const failed = gating.filter((c) => c.status === "FAIL").length;
      const passed = gating.filter((c) => c.status === "PASS").length;
      // Required checks with no recorded row at all are still outstanding borrower work — count
      // them as pending so a barely-started application can't read as "all checks passed".
      const cleared = new Set(
        gating.filter((c) => c.status === "PASS" || c.status === "REVIEW").map((c) => c.checkType),
      );
      const recorded = new Set(gating.map((c) => c.checkType));
      const missingRequired = REQUIRED_CHECKS.filter((t) => !recorded.has(t)).length;
      const pendingReview =
        gating.filter((c) => c.status === "PENDING" || c.status === "REVIEW").length + missingRequired;
      const requiredCleared = REQUIRED_CHECKS.every((t) => cleared.has(t));
      const lastUpdate = checks.reduce<string | null>(
        (acc, c) => (c.updatedAt && (!acc || c.updatedAt > acc) ? c.updatedAt : acc),
        null,
      );
      const bucket: Bucket =
        failed > 0 ? "failures" : !requiredCleared || pendingReview > 0 ? "awaiting" : "passed";
      out.push({
        applicationId,
        customerId: checks.find((c) => c.customerId != null)?.customerId ?? null,
        borrowerName: checks.find((c) => c.borrowerName != null)?.borrowerName ?? null,
        borrowerMobile: checks.find((c) => c.borrowerMobile != null)?.borrowerMobile ?? null,
        total: gating.length + missingRequired,
        employmentStatus: employment?.status ?? null,
        passed,
        failed,
        pendingReview,
        lastUpdate,
        bucket,
      });
    }

    // "Not started": KYC_PENDING applications that have no verification rows yet.
    const term = debounced.toLowerCase();
    for (const app of pendingQ.data ?? []) {
      if (byApp.has(app.id)) continue;
      if (
        term &&
        !`${app.id} ${app.customerId ?? ""} ${app.customerName ?? ""} ${app.customerMobile ?? ""}`
          .toLowerCase()
          .includes(term)
      )
        continue;
      out.push({
        applicationId: app.id,
        customerId: app.customerId,
        borrowerName: app.customerName ?? null,
        borrowerMobile: app.customerMobile ?? null,
        total: 0,
        passed: 0,
        failed: 0,
        pendingReview: 0,
        employmentStatus: null,
        lastUpdate: null,
        bucket: "notStarted",
      });
    }
    return out;
  }, [data?.rows, pendingQ.data, debounced]);

  const grouped = React.useMemo(() => {
    const g: Record<Bucket, AppCard[]> = { failures: [], awaiting: [], passed: [], notStarted: [] };
    for (const c of cards) g[c.bucket].push(c);
    // Newest activity first within a bucket (not-started apps have no timestamp — leave order stable).
    for (const k of Object.keys(g) as Bucket[]) {
      g[k].sort((a, b) => (b.lastUpdate ?? "").localeCompare(a.lastUpdate ?? ""));
    }
    return g;
  }, [cards]);

  const loading = q.isLoading || pendingQ.isLoading;
  const anyError = q.error || pendingQ.error;

  return (
    <div>
      <PageHeader
        title="Verification dashboard"
        subtitle="Every application that needs a KYC decision, grouped by where it stands — failures first, then awaiting the borrower, then cleared."
      >
        <button
          onClick={() => {
            q.refetch();
            pendingQ.refetch();
          }}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching || pendingQ.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <PermissionGate permission="kyc:approve" fallback={<NoAccessNotice />}>
        <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-5">
          <Tile label="Pending" value={data?.pending} valueClass="text-muted" />
          <Tile label="Failed" value={data?.failed} valueClass="text-error-700" />
          <Tile label="In review" value={data?.review} valueClass="text-warning-800" />
          <Tile label="Passed" value={data?.passed} valueClass="text-success-700" />
          <Tile label="Never run" value={data?.neverRun} valueClass="text-muted" />
        </div>

        <div className="mb-4 flex flex-wrap items-center justify-end gap-3">
          <Input
            aria-label="Search by borrower / application / customer id"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search borrower / app # / customer #"
            leftIcon={<Search size={15} />}
            className="!mb-0"
            inputClassName="w-72"
          />
        </div>

        {loading ? (
          <div className="h-40 animate-pulse rounded bg-grey-100" />
        ) : anyError ? (
          <p className="rounded border border-line bg-white px-5 py-4 text-sm text-error-700 shadow-sm">
            {errMessage(q.error ?? pendingQ.error)}
          </p>
        ) : cards.length === 0 ? (
          <p className="rounded border border-line bg-white px-5 py-8 text-center text-sm text-muted shadow-sm">
            No applications to verify{debounced ? ` for “${debounced}”` : ""}.
          </p>
        ) : (
          <div className="space-y-6">
            {BUCKETS.map((b) => {
              const list = grouped[b.key];
              if (list.length === 0) return null;
              return (
                <section key={b.key}>
                  <h2 className="mb-2 flex items-center gap-2 text-sm font-semibold text-navy">
                    <span className={b.accent}>{b.label}</span>
                    <span className="rounded-full bg-grey-100 px-2 py-0.5 text-xs font-semibold text-muted">{list.length}</span>
                  </h2>
                  <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                    {list.map((c) => (
                      <AppCardTile key={c.applicationId} card={c} onOpen={() => setSelected(c)} />
                    ))}
                  </div>
                </section>
              );
            })}
          </div>
        )}
      </PermissionGate>

      {selected && (
        <Dialog
          open
          onClose={() => setSelected(null)}
          className="!max-w-3xl !w-[min(52rem,94vw)]"
          aria-label="Verification checks"
        >
          <div className="flex items-center justify-between gap-3 border-b border-line pb-3">
            <div>
              <h3 className="font-serif text-lg text-navy">
                {selected.borrowerName ?? (selected.customerId != null ? `Customer #${selected.customerId}` : "Application")}{" "}
                <span className="text-sm font-normal text-muted">app #{selected.applicationId}</span>
              </h3>
              {selected.customerId != null && (
                <p className="text-xs text-muted">
                  Customer #{selected.customerId}
                  {selected.borrowerMobile ? ` · ${selected.borrowerMobile}` : ""}
                </p>
              )}
            </div>
            <button
              onClick={() => setSelected(null)}
              className="rounded p-1 text-muted hover:bg-grey-100 hover:text-ink"
              aria-label="Close"
            >
              <X size={18} />
            </button>
          </div>
          <div className="max-h-[70vh] overflow-y-auto pr-1">
            <VerificationChecksPanel applicationId={selected.applicationId} />
          </div>
        </Dialog>
      )}
    </div>
  );
}

function Tile({ label, value, valueClass }: { label: string; value: number | undefined; valueClass: string }) {
  return (
    <div className="rounded border border-line bg-white p-4 shadow-sm">
      <div className="text-xs font-semibold uppercase tracking-wide text-muted">{label}</div>
      <div className={`mt-1 font-serif text-2xl font-bold ${valueClass}`}>{value ?? "—"}</div>
    </div>
  );
}

/**
 * The EPFO/UAN outcome as a standalone chip. Deliberately outside the passed/failed/pending counts:
 * this check gates nothing, so it must not move an application between buckets (see
 * {@link ADVISORY_CHECKS}). A REVIEW here reads "we could not confirm employment", not "the borrower
 * still owes us a step" — hence the neutral wording rather than a warning colour.
 */
function EmploymentChip({ status }: { status: string | null }) {
  if (!status) return null;
  const label =
    status === "PASS" ? "EPFO ok" : status === "FAIL" ? "EPFO failed" : "EPFO unconfirmed";
  const tone =
    status === "PASS"
      ? "bg-success-100 text-success-700"
      : status === "FAIL"
        ? "bg-error-100 text-error-700"
        : "bg-grey-100 text-muted";
  return <span className={`rounded-full px-1.5 py-0.5 font-semibold ${tone}`}>{label}</span>;
}

function AppCardTile({ card, onOpen }: { card: AppCard; onOpen: () => void }) {
  const pct = card.total > 0 ? Math.round((card.passed / card.total) * 100) : 0;
  return (
    <button
      onClick={onOpen}
      className="group flex flex-col rounded border border-line bg-white p-4 text-left shadow-sm transition hover:border-navy/40 hover:shadow"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="truncate font-semibold text-navy">
            {card.borrowerName ?? (card.customerId != null ? `Customer #${card.customerId}` : "—")}
          </div>
          <div className="text-xs text-muted">
            app #{card.applicationId}
            {card.customerId != null ? ` · cust #${card.customerId}` : ""}
            {card.borrowerMobile ? ` · ${card.borrowerMobile}` : ""}
          </div>
        </div>
        <ChevronRight size={16} className="mt-0.5 flex-shrink-0 text-muted transition group-hover:text-navy" />
      </div>

      {card.total > 0 ? (
        <>
          <div className="mt-3 flex items-center justify-between text-xs">
            <span className="font-semibold text-navy">{card.passed}/{card.total} passed</span>
            <span className="flex items-center gap-1.5">
              {card.failed > 0 && <span className="rounded-full bg-error-100 px-1.5 py-0.5 font-semibold text-error-700">{card.failed} failed</span>}
              {card.pendingReview > 0 && <span className="rounded-full bg-warning-100 px-1.5 py-0.5 font-semibold text-warning-800">{card.pendingReview} pending</span>}
              <EmploymentChip status={card.employmentStatus} />
            </span>
          </div>
          <div className="mt-1.5 h-1.5 w-full overflow-hidden rounded-full bg-grey-200">
            <div className="h-full rounded-full bg-success-600 transition-all" style={{ width: `${pct}%` }} />
          </div>
        </>
      ) : (
        <div className="mt-3 text-xs text-muted">No verification checks started yet.</div>
      )}

      <div className="mt-3 text-[8.8px] text-muted">
        {card.lastUpdate ? `Updated ${formatDateTime(card.lastUpdate)}` : "Awaiting first check"}
      </div>
    </button>
  );
}
