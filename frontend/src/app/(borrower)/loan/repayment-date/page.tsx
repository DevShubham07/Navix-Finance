"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { CalendarCheck, Loader2, Lock } from "lucide-react";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { useOffer, completeOfferStep, nextOfferRoute, prevOfferRoute } from "@/lib/offer";
import { formatDate } from "@/lib/utils";

const MONTHS = ["January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December"];
const WEEKDAY_INITIALS = ["S", "M", "T", "W", "T", "F", "S"];

/**
 * Screen 2: the repayment date, preselected and **not changeable** (revamp.md decision 35).
 *
 * <p>The month grid is presentational — it exists so the date reads as a real day the borrower can
 * see coming, not a number in a form. Both credit roles can set this date; the borrower never can,
 * which is why every cell but one is inert.
 *
 * <p>`Lock this date` and then `Request for loan` are the same button in two states: the first press
 * acknowledges the date, the second continues. That is the interaction the spec asks for.
 */
export default function RepaymentDatePage() {
  const router = useRouter();
  const { appId, app, isLoading } = useOffer();
  const [locked, setLocked] = React.useState(false);
  const [busy, setBusy] = React.useState(false);

  const iso = app?.approvedRepaymentDate ?? null;
  const due = React.useMemo(() => (iso ? new Date(`${iso}T00:00:00`) : null), [iso]);

  const submit = async () => {
    if (!locked) {
      setLocked(true);
      return;
    }
    if (appId == null) return;
    setBusy(true);
    await completeOfferStep(appId, "OFFER_REPAYMENT_DATE", router, nextOfferRoute("repayment-date"));
  };

  if (isLoading || !app) {
    return (
      <p className="flex items-center gap-2 text-sm text-muted">
        <Loader2 size={16} className="animate-spin" /> Loading your offer…
      </p>
    );
  }

  return (
    <div>
      <div className="form-card">
        <p className="lead mb-1">Your repayment date is set by our credit team.</p>
        <p className="mb-6 text-sm text-muted">
          Repay in one instalment on this date. You can always pay earlier — interest is charged only
          to the day you pay.
        </p>

        {due ? (
          <>
            <MonthGrid due={due} />
            <div className="mt-6 flex items-start gap-3 rounded border border-line bg-navy-tint p-4">
              <CalendarCheck size={18} className="mt-0.5 flex-shrink-0 text-navy" />
              <div>
                <p className="font-serif text-lg font-bold text-navy">{formatDate(due)}</p>
                <p className="flex items-center gap-1.5 text-xs text-muted">
                  <Lock size={12} /> Fixed by our credit team — this date cannot be changed.
                </p>
              </div>
            </div>
          </>
        ) : (
          <p className="text-sm text-muted">
            Your repayment date will appear here once our credit team sets it.
          </p>
        )}
      </div>

      <WizardActions
        backHref={prevOfferRoute("repayment-date")}
        continueLabel={locked ? "Request for loan" : "Lock this date"}
        onContinue={submit}
        loading={busy}
        disabled={busy || !due}
      />
      <Reassurance />
    </div>
  );
}

/** The due date's own month, with every other day inert — read-only by design. */
function MonthGrid({ due }: { due: Date }) {
  const year = due.getFullYear();
  const month = due.getMonth();
  const daysInMonth = new Date(year, month + 1, 0).getDate();
  const leading = new Date(year, month, 1).getDay();
  const cells: Array<number | null> = [
    ...Array<null>(leading).fill(null),
    ...Array.from({ length: daysInMonth }, (_, i) => i + 1),
  ];

  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm">
      <p className="mb-3 text-center font-serif text-base font-semibold text-navy">
        {MONTHS[month]} {year}
      </p>
      <div className="grid grid-cols-7 gap-1 text-center">
        {WEEKDAY_INITIALS.map((d, i) => (
          <span key={i} className="pb-1 text-xs font-semibold uppercase text-muted">
            {d}
          </span>
        ))}
        {cells.map((day, i) =>
          day == null ? (
            <span key={`pad-${i}`} />
          ) : (
            <span
              key={day}
              aria-current={day === due.getDate() ? "date" : undefined}
              className={
                day === due.getDate()
                  ? "flex h-9 items-center justify-center rounded bg-navy font-semibold text-white"
                  : "flex h-9 items-center justify-center rounded text-sm text-muted"
              }
            >
              {day}
            </span>
          ),
        )}
      </div>
    </div>
  );
}
