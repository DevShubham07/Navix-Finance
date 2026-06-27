"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { ShieldCheck } from "lucide-react";
import { ONBOARDING_STEPS as STEPS } from "@/lib/onboarding";

export default function SignupLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const seg = pathname.split("/").filter(Boolean).pop() ?? "mobile-otp";
  // Co-applicant is conditional and absent from the linear order — show it as the
  // address/bureau-adjacent step rather than resetting the bar to step 1.
  const found = STEPS.findIndex((s) => s.seg === seg);
  const idx = found >= 0 ? found : 0;
  const step = STEPS[idx] ?? STEPS[0];
  const pct = Math.round(((idx + 1) / STEPS.length) * 100);

  // Persist the current step so the dashboard can resume here after Save & exit.
  React.useEffect(() => {
    if (typeof window !== "undefined") {
      localStorage.setItem("navix.onboarding.lastStep", seg);
    }
  }, [seg]);

  return (
    <div className="bg-ivory">
      <div className="border-b border-line bg-white">
        <div className="container py-5">
          <div className="mb-2 flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-gold-dark">
              Step {idx + 1} of {STEPS.length}
            </span>
            <span className="flex items-center gap-1.5 text-xs text-muted">
              <ShieldCheck size={14} className="text-success-600" /> Secure &amp; encrypted
            </span>
          </div>
          <div className="mb-2 flex items-baseline justify-between gap-3">
            <h2 className="font-serif text-xl text-navy">{step.label}</h2>
            <Link href="/dashboard" className="btn btn-sm btn-outline">
              Save &amp; exit
            </Link>
          </div>
          <div className="h-1.5 w-full overflow-hidden rounded-full bg-grey-200">
            <div className="h-full rounded-full bg-gold transition-all" style={{ width: `${pct}%` }} />
          </div>
        </div>
      </div>
      <div className="container max-w-content py-10">{children}</div>
    </div>
  );
}
