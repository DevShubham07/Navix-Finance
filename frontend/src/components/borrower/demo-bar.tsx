"use client";

import * as React from "react";
import { usePathname, useRouter } from "next/navigation";
import { FlaskConical, X, RotateCcw, Sparkles } from "lucide-react";
import { useBorrowerJourney, type BorrowerStatus } from "@/lib/mock/borrower";
import { SCENARIOS } from "@/lib/mock/scenarios";
import { signInBorrower, signOutBorrower } from "@/lib/mock/session";
import { useMounted } from "@/hooks/use-mounted";

/** Landing route for a seeded journey so the tester picks up at the right screen. */
export function routeForStatus(status: BorrowerStatus): string {
  switch (status) {
    case "NEW":
      return "/signup/pan";
    case "APPLIED":
    case "UNDER_REVIEW":
    case "DECLINED":
      return "/loan/status";
    case "APPROVED":
      return "/loan/apply";
    case "DOCS_SIGNED":
      return "/loan/documents";
    case "BANK_VERIFIED":
      return "/loan/bank-verify";
    case "ACTIVE":
    case "REPAID":
      return "/dashboard";
    case "OVERDUE":
      return "/repay";
    default:
      return "/dashboard";
  }
}

/**
 * Floating demo control (clickable prototype only). Seeds a reproducible
 * persona and jumps to the matching screen, or starts a clean application.
 * Mount once in the borrower layout.
 */
export function DemoBar() {
  const [open, setOpen] = React.useState(false);
  const router = useRouter();
  const pathname = usePathname();
  const mounted = useMounted();
  const loadScenario = useBorrowerJourney((s) => s.loadScenario);
  const beginApplication = useBorrowerJourney((s) => s.beginApplication);

  // Never show on the public marketing site.
  if (!mounted || pathname === "/" || pathname.startsWith("/(marketing)")) return null;

  const pick = (id: string) => {
    const sc = SCENARIOS.find((s) => s.id === id);
    if (!sc) return;
    loadScenario(sc);
    signInBorrower(sc.applicant.fullName ?? "Demo User", sc.applicant.mobile ?? "98765 43210");
    setOpen(false);
    router.push(routeForStatus(sc.forceDecline ? "DECLINED" : sc.status));
  };

  const startFresh = () => {
    beginApplication();
    signOutBorrower();
    setOpen(false);
    router.push("/signup/pan");
  };

  return (
    <>
      {open && (
        <div
          className="fixed inset-0 z-[190] bg-navy/30 backdrop-blur-[1px]"
          onClick={() => setOpen(false)}
          aria-hidden
        />
      )}
      <div data-demobar className="fixed bottom-4 right-4 z-[200] flex flex-col items-end gap-3">
        {open && (
          <div className="w-[min(92vw,360px)] overflow-hidden rounded-lg border border-line bg-white shadow-lg">
            <div className="flex items-center justify-between bg-navy px-4 py-3 text-white">
              <span className="flex items-center gap-2 font-serif text-sm font-semibold">
                <FlaskConical size={16} className="text-gold" /> Demo scenarios
              </span>
              <button onClick={() => setOpen(false)} aria-label="Close demo panel" className="rounded p-1 hover:bg-white/10">
                <X size={16} />
              </button>
            </div>
            <div className="max-h-[60vh] overflow-y-auto p-2">
              <button
                onClick={startFresh}
                className="mb-1 flex w-full items-center gap-3 rounded px-3 py-2.5 text-left transition hover:bg-navy-tint"
              >
                <span className="grid h-8 w-8 flex-shrink-0 place-items-center rounded-full bg-gold-soft text-gold-dark">
                  <Sparkles size={15} />
                </span>
                <span>
                  <span className="block text-sm font-semibold text-ink">Start a fresh application</span>
                  <span className="block text-xs text-muted">Walk the full signup from a blank slate</span>
                </span>
              </button>
              <div className="my-1 border-t border-grey-200" />
              {SCENARIOS.map((s) => (
                <button
                  key={s.id}
                  onClick={() => pick(s.id)}
                  className="flex w-full items-start gap-3 rounded px-3 py-2.5 text-left transition hover:bg-navy-tint"
                >
                  <span className="mt-0.5 grid h-8 w-8 flex-shrink-0 place-items-center rounded-full bg-navy-tint font-serif text-xs font-bold text-navy">
                    {s.label.match(/Category ([A-D])/)?.[1] ?? "•"}
                  </span>
                  <span>
                    <span className="block text-sm font-semibold text-ink">{s.label}</span>
                    <span className="block text-xs leading-snug text-muted">{s.description}</span>
                  </span>
                </button>
              ))}
            </div>
            <div className="border-t border-grey-200 px-3 py-2 text-[11px] text-muted">
              Prototype control · seeds mock data only
            </div>
          </div>
        )}
        <button
          onClick={() => setOpen((o) => !o)}
          className="btn btn-navy btn-sm shadow-lg"
          aria-expanded={open}
          aria-label="Demo scenarios"
        >
          {open ? <RotateCcw size={15} /> : <FlaskConical size={15} />}
          <span className="hidden sm:inline">Demo</span>
        </button>
      </div>
    </>
  );
}
