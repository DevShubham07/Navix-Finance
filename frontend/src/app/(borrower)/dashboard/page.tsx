"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Wallet, CalendarClock, CalendarDays, ArrowRight, AlertTriangle, CheckCircle2, Sparkles, FileClock,
} from "lucide-react";
import { Badge } from "@/components/ui";
import type { BorrowerStatus } from "@/lib/domain/borrower";
import {
  useLiveApplication,
  useBorrowerSession,
  updateBorrowerName,
  appStatusToStage,
  canChooseAmount,
  isTerminalBad,
} from "@/lib/api/live-journey";
import {
  borrowerApi,
  rupeesToPaise,
  paiseToINR,
  statusLabel,
  type ApplicationStatus,
} from "@/lib/api/applications";
import { useOnboardingStore } from "@/stores/application-store";
import { eligibleLimit, daysBetween } from "@/lib/calc/loan-math";
import { formatINR0, formatDate } from "@/lib/utils";

export default function DashboardPage() {
  const session = useBorrowerSession();
  const { appId, app, loan, isLoading } = useLiveApplication();
  const draft = useOnboardingStore();
  const queryClient = useQueryClient();

  // Eligible limit (25% of salary) — from the persisted backend profile, else the onboarding draft.
  const profileQuery = useQuery({
    queryKey: ["live-profile", appId],
    queryFn: () => borrowerApi.getProfile(appId as number),
    enabled: appId != null,
  });

  // Self-heal the session display name: an OTP-only login defaults to "Borrower" until a
  // name exists; once the borrower has saved one (backend profile or onboarding draft), map
  // it onto the session so the header/greeting use their real name. Runs once per change.
  const knownName = (profileQuery.data?.fullName || draft.fullName || "").trim();
  React.useEffect(() => {
    const current = session.data?.name?.trim();
    if (session.data && knownName && (!current || current === "Borrower") && knownName !== "Borrower") {
      updateBorrowerName(knownName).then(() => queryClient.invalidateQueries({ queryKey: ["borrower-me"] }));
    }
  }, [session.data, knownName, queryClient]);
  const salaryPaise =
    profileQuery.data?.monthlySalaryPaise ??
    (draft.monthlySalary ? rupeesToPaise(draft.monthlySalary) : 0);
  const limitRupees = eligibleLimit(Math.round(salaryPaise / 100));

  // Date of birth from the backend identity record (captured at the PAN/Aadhaar step). Shown
  // whenever it's on file — regardless of whether KYC is fully verified or a loan is active.
  const dobDisplay = formatDOB(profileQuery.data?.dob ?? null);

  // "Repaid" is the sum of VERIFIED payments — not total − outstanding, since the (now penalty- and
  // prepayment-aware) outstanding can be below the on-time total without any payment being made.
  const paymentsQuery = useQuery({
    queryKey: ["live-payments", loan?.id],
    queryFn: () => borrowerApi.repayments(loan!.id as number),
    enabled: loan?.id != null,
  });
  const repaidRupees =
    (paymentsQuery.data ?? [])
      .filter((p) => p.status === "VERIFIED")
      .reduce((sum, p) => sum + p.amountPaise, 0) / 100;

  const stage = appStatusToStage(app);
  // Prefer a real name (the OTP-login default "Borrower" is not one) — session if it's been
  // mapped, else the known profile/draft name, else a friendly fallback.
  const realSessionName =
    session.data?.name && session.data.name !== "Borrower" ? session.data.name : "";
  const firstName =
    (realSessionName || knownName || session.data?.name || "there").split(" ")[0] || "there";
  const active = app?.status === "ACTIVE" || app?.status === "OVERDUE";
  const closed = app?.status === "CLOSED";
  const declined = isTerminalBad(app);

  // Resume from the last wizard step the user was on (persisted by the signup layout).
  const [lastOnboardingStep, setLastOnboardingStep] = React.useState<string | null>(null);
  React.useEffect(() => {
    setLastOnboardingStep(localStorage.getItem("navix.onboarding.lastStep"));
  }, []);

  const continueHref = app?.status === "DRAFT"
    ? `/signup/${lastOnboardingStep ?? "mobile-otp"}`
    : canChooseAmount(app) ? "/loan/apply" : "/loan/status";

  if (isLoading && !app) {
    return <div className="container py-10"><div className="h-72 animate-pulse rounded border border-line bg-white" /></div>;
  }

  return (
    <div className="container py-10">
      <div className="mb-7 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="mb-0">Hi, {firstName}</h1>
          <p className="mt-1 text-muted">Here&apos;s where your advance stands.</p>
        </div>
        <StatusChip status={stage} />
      </div>

      <div className="grid gap-6 lg:grid-cols-[1fr_minmax(0,340px)]">
        <div>
          {active && loan ? (
            <LoanCard
              status={app?.status === "OVERDUE" ? "OVERDUE" : "ACTIVE"}
              total={loan.totalRepayablePaise / 100}
              outstanding={loan.outstandingPaise / 100}
              penalty={Math.max(0, (loan.outstandingPaise - loan.totalRepayablePaise) / 100)}
              principal={loan.principalPaise / 100}
              repaid={repaidRupees}
              dueISO={loan.dueDate ?? new Date().toISOString()}
            />
          ) : closed ? (
            <PreApprovedBanner
              limitRupees={limitRupees}
              href="/reloan"
              note="Your previous advance is fully repaid — you're in good standing."
            />
          ) : declined ? (
            <InfoCard
              icon={<AlertTriangle size={26} />}
              tone="error"
              title="Application not approved"
              body="Your application didn't meet current credit policy. See your status page for details."
              cta={{ href: "/loan/status", label: "View status" }}
            />
          ) : app ? (
            <InfoCard
              icon={<FileClock size={26} />}
              tone="navy"
              title="Application in progress"
              body="Pick up where you left off — track your application or choose your amount."
              extra={
                <div className="mb-5 rounded border border-line bg-grey-100 px-4 py-3">
                  <div className="mb-1 flex items-center justify-between gap-2">
                    <span className="text-sm font-semibold text-navy">Application status</span>
                    <StatusChip status={stage} />
                  </div>
                  <p className="text-xs text-muted">{stageDescription(stage)}</p>
                  <Link href="/loan/status" className="mt-1 inline-block text-xs font-semibold text-gold-dark hover:underline">
                    View full status &amp; timeline →
                  </Link>
                </div>
              }
              cta={{ href: continueHref, label: "Continue" }}
            />
          ) : (
            <PreApprovedBanner limitRupees={limitRupees} href="/signup/mobile-otp" />
          )}
        </div>

        <aside className="flex flex-col gap-4">
          {dobDisplay && (
            <div className="rounded border border-line bg-white p-5 shadow-sm">
              <div className="mb-1 flex items-center gap-2 text-sm font-semibold text-navy">
                <CalendarDays size={16} /> Date of birth
              </div>
              <div className="font-serif text-2xl font-bold text-navy">{dobDisplay}</div>
              <p className="mt-1 text-xs text-muted">As per your identity records</p>
            </div>
          )}

          <div className="rounded border border-line bg-white p-5 shadow-sm">
            <div className="mb-1 flex items-center gap-2 text-sm font-semibold text-navy">
              <Wallet size={16} /> Eligible limit
            </div>
            <div className="font-serif text-2xl font-bold text-navy">
              {limitRupees > 0 ? formatINR0(limitRupees) : "—"}
            </div>
            <p className="mt-1 text-xs text-muted">
              {limitRupees > 0 ? "25% of your monthly salary" : "Add your salary to see your limit"}
            </p>
          </div>

          <div className="rounded border border-line bg-white p-5 shadow-sm">
            <div className="mb-2 text-sm font-semibold text-navy">Quick links</div>
            <ul className="text-sm">
              <li><Link href="/loan/status" className="-mx-2 block rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy">Application status</Link></li>
              <li><Link href="/repay" className="-mx-2 block rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy">Repay / prepay</Link></li>
              <li><Link href="/profile" className="-mx-2 block rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy">Profile &amp; KYC</Link></li>
            </ul>
          </div>
        </aside>
      </div>

      <ApplicationsHistory />
    </div>
  );
}

/**
 * A compact, newest-first roll-up of every application the borrower has made, shown full-width
 * below the main grid. Shares the React-Query cache with `/loans` (same `["my-apps"]` key), so the
 * two stay in sync. Each row links to the live status page; "View all" deep-links to `/loans` for
 * the full per-loan economics.
 */
function ApplicationsHistory() {
  const q = useQuery({ queryKey: ["my-apps"], queryFn: borrowerApi.myApplications });
  const apps = q.data ?? [];

  return (
    <section className="mt-10">
      <div className="mb-3 flex items-center justify-between gap-3">
        <h2 className="flex items-center gap-2 font-serif text-lg font-semibold text-navy">
          <FileClock size={18} /> Your applications
        </h2>
        {apps.length > 0 && (
          <Link href="/loans" className="text-sm font-semibold text-gold-dark hover:underline">
            View all →
          </Link>
        )}
      </div>

      {q.isLoading ? (
        <div className="space-y-3">
          {[0, 1].map((i) => (
            <div key={i} className="h-16 animate-pulse rounded border border-line bg-white" />
          ))}
        </div>
      ) : q.error ? (
        <div className="rounded border border-error-100 bg-error-50 p-5 text-sm text-error-700">
          Could not load your applications. Please refresh and try again.
        </div>
      ) : apps.length === 0 ? (
        <p className="rounded border border-dashed border-line bg-white px-5 py-4 text-sm text-muted">
          No applications yet.
        </p>
      ) : (
        <div className="space-y-3">
          {apps.map((app) => (
            <Link
              key={app.id}
              href="/loan/status"
              className="flex flex-wrap items-center justify-between gap-3 rounded border border-line bg-white p-5 shadow-sm transition hover:border-gold hover:shadow"
            >
              <div className="min-w-0">
                <div className="font-serif text-base font-semibold text-navy">Application #{app.id}</div>
                <div className="mt-0.5 text-sm text-muted">
                  Requested {paiseToINR(app.amountRequestedPaise)}
                  {app.purpose ? ` · ${app.purpose}` : ""}
                </div>
              </div>
              <div className="flex items-center gap-3">
                <Badge variant={appStatusVariant(app.status)}>{statusLabel(app.status)}</Badge>
                <ArrowRight size={16} className="text-muted" />
              </div>
            </Link>
          ))}
        </div>
      )}
    </section>
  );
}

/** Colour an application status: active=green, bad=red, terminal-neutral=grey, in-flight=blue. */
function appStatusVariant(status: ApplicationStatus): React.ComponentProps<typeof Badge>["variant"] {
  switch (status) {
    case "ACTIVE":
      return "success";
    case "CLOSED":
    case "CANCELLED":
      return "neutral";
    case "OVERDUE":
    case "DEFAULTED":
    case "WRITTEN_OFF":
    case "REJECTED":
    case "KYC_REJECTED":
    case "DISBURSEMENT_FAILED":
      return "error";
    default:
      return "info";
  }
}

/**
 * Format an ISO `yyyy-MM-dd` date of birth for display (e.g. "15 Aug 1992"). Builds a *local*
 * Date from the parts so the day never shifts across timezones (`new Date("yyyy-MM-dd")` parses
 * as UTC midnight, which can roll back a day in negative-offset zones). Returns null when absent.
 */
function formatDOB(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const [y, m, d] = iso.split("-").map((n) => Number(n));
  if (!y || !m || !d) return null;
  return new Date(y, m - 1, d).toLocaleDateString("en-IN", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  });
}

/** One-line, customer-friendly description of where the application currently stands. */
function stageDescription(status: BorrowerStatus): string {
  const map: Partial<Record<BorrowerStatus, string>> = {
    NEW: "Continue your application where you left off.",
    APPLIED: "Submitted — your application is in the queue.",
    UNDER_REVIEW: "Our team is verifying your KYC and details.",
    APPROVED: "KYC approved — choose your amount to continue.",
    DOCS_SIGNED: "Documents signed — moving to disbursal.",
    DISBURSING: "Approved — your disbursal is being arranged.",
  };
  return map[status] ?? "Track your application for the latest update.";
}

function StatusChip({ status }: { status: BorrowerStatus }) {
  const map: Record<BorrowerStatus, { label: string; variant: React.ComponentProps<typeof Badge>["variant"] }> = {
    NEW: { label: "Not started", variant: "neutral" },
    APPLIED: { label: "Applied", variant: "info" },
    UNDER_REVIEW: { label: "Under review", variant: "warning" },
    APPROVED: { label: "KYC approved", variant: "success" },
    DOCS_SIGNED: { label: "Docs signed", variant: "info" },
    BANK_VERIFIED: { label: "Bank verified", variant: "info" },
    DISBURSING: { label: "Processing", variant: "warning" },
    ACTIVE: { label: "Active loan", variant: "success" },
    REPAID: { label: "Repaid", variant: "neutral" },
    DECLINED: { label: "Declined", variant: "error" },
    OVERDUE: { label: "Overdue", variant: "error" },
  };
  const m = map[status];
  return <Badge variant={m.variant}>{m.label}</Badge>;
}

function LoanCard({
  status, total, outstanding, penalty, principal, repaid, dueISO,
}: {
  status: BorrowerStatus;
  total: number; outstanding: number; penalty: number; principal: number; repaid: number; dueISO: string;
}) {
  const pct = total > 0 ? Math.min(100, Math.round((repaid / total) * 100)) : 0;
  const due = new Date(dueISO);
  const days = daysBetween(new Date(), due);
  const overdue = status === "OVERDUE" || days < 0;

  if (status === "REPAID") {
    return (
      <div className="rounded border border-success-100 bg-success-50/50 p-7 shadow-sm">
        <span className="mb-3 grid h-14 w-14 place-items-center rounded-full bg-success-50 text-success-600">
          <CheckCircle2 size={28} />
        </span>
        <h2 className="text-2xl">Loan fully repaid</h2>
        <p className="mb-4 text-muted">Your {formatINR0(principal)} advance is closed and in good standing. Ready when you need it again.</p>
        <Link href="/reloan" className="btn btn-gold">Borrow again <ArrowRight size={16} /></Link>
      </div>
    );
  }

  return (
    <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
      <div className={`flex items-center justify-between gap-3 px-6 py-4 text-white ${overdue ? "bg-error-600" : "bg-navy"}`}>
        <span className="font-serif text-base font-semibold">{overdue ? "Repayment overdue" : "Active advance"}</span>
        <span className="text-sm text-white/80">{overdue ? `${Math.abs(days)} days past due` : `Due in ${days} days`}</span>
      </div>
      <div className="p-6">
        <div className="text-sm text-muted">Total outstanding</div>
        <div className="font-serif text-3xl font-bold text-navy sm:text-4xl">{formatINR0(outstanding)}</div>
        {penalty > 0 && (
          <div className="mt-1 flex items-center gap-1.5 text-sm font-semibold text-error-600">
            <AlertTriangle size={14} /> Includes {formatINR0(penalty)} late penalty (2%/day, cap 30d)
          </div>
        )}

        <div className="mt-5 h-2 w-full overflow-hidden rounded-full bg-grey-200">
          <div className="h-full rounded-full bg-success-600 transition-all" style={{ width: `${pct}%` }} />
        </div>
        <div className="mt-1.5 flex justify-between text-xs text-muted">
          <span>{formatINR0(repaid)} repaid</span>
          <span>{formatINR0(total)} total</span>
        </div>

        <div className="mt-5 flex flex-wrap items-center gap-3 border-t border-grey-200 pt-5 text-sm text-muted">
          <span className="flex items-center gap-1.5"><CalendarClock size={15} /> Due {formatDate(due)}</span>
        </div>

        <Link href="/repay" className="btn btn-gold btn-block mt-5">
          <Wallet size={16} /> {overdue ? "Pay now" : "Repay / prepay"}
        </Link>
      </div>
    </div>
  );
}

/**
 * The pre-approved offer a borrower sees on the dashboard whenever no loan is in
 * process — a fresh applicant, or after a previous advance has been fully repaid.
 * Brand-styled (navy gradient + gold CTA). When the salary-derived eligible limit
 * is known it surfaces the real "up to ₹X pre-approved" amount; otherwise it stays
 * generic. Benefits are product-accurate (single salary-day repayment — no EMI).
 */
function PreApprovedBanner({
  limitRupees, href, ctaLabel = "Check Now", note,
}: {
  limitRupees: number; href: string; ctaLabel?: string; note?: string;
}) {
  return (
    <div className="relative overflow-hidden rounded-lg border border-navy-900 bg-gradient-to-br from-navy-900 via-navy to-navy-700 p-7 shadow-lg sm:p-8">
      {/* Decorative gold rings — the on-brand echo of the reference's accent swoosh. */}
      <div aria-hidden className="pointer-events-none absolute -right-16 -top-12 h-56 w-56 rounded-full border-[18px] border-gold/15" />
      <div aria-hidden className="pointer-events-none absolute -bottom-20 right-2 h-44 w-44 rounded-full bg-gold/5" />

      <div className="relative max-w-md">
        <span className="inline-flex items-center gap-1.5 rounded-full bg-gold/20 px-3 py-1 text-xs font-bold uppercase tracking-wide text-gold-soft">
          <Sparkles size={13} />
          {limitRupees > 0 ? `Up to ${formatINR0(limitRupees)} pre-approved` : "Pre-approved"}
        </span>

        <h2 className="mt-3 font-serif text-3xl font-bold text-white sm:text-4xl">
          Pre-Approved Personal Loan
        </h2>
        <p className="mt-2 text-sm text-navix-100">
          No paperwork&nbsp; | &nbsp;One repayment on salary day&nbsp; | &nbsp;Instant disbursal
        </p>
        {note && <p className="mt-2 text-xs text-navix-200">{note}</p>}

        <Link href={href} className="btn btn-gold mt-6">
          {ctaLabel} <ArrowRight size={16} />
        </Link>

        <p className="mt-4 text-[11px] text-white/55">*T&amp;C Apply</p>
      </div>
    </div>
  );
}

function InfoCard({
  icon, title, body, cta, tone, extra,
}: {
  icon: React.ReactNode; title: string; body: string;
  cta?: { href: string; label: string }; tone: "navy" | "error";
  extra?: React.ReactNode;
}) {
  return (
    <div className={`rounded border p-7 shadow-sm ${tone === "error" ? "border-error-100 bg-error-50/40" : "border-line bg-white"}`}>
      <span className={`mb-3 grid h-14 w-14 place-items-center rounded-full ${tone === "error" ? "bg-error-50 text-error-600" : "bg-navy-tint text-navy"}`}>
        {icon}
      </span>
      <h2 className="text-2xl">{title}</h2>
      <p className="mb-4 text-muted">{body}</p>
      {extra}
      {cta && <Link href={cta.href} className="btn btn-gold">{cta.label} <ArrowRight size={16} /></Link>}
    </div>
  );
}
