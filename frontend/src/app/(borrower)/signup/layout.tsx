"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { ShieldCheck } from "lucide-react";

/** Canonical sign-up order (product flow §3.1). */
const STEPS: Array<{ seg: string; label: string }> = [
  { seg: "pan", label: "PAN verification" },
  { seg: "mobile-otp", label: "Mobile & OTP" },
  { seg: "employment", label: "Employment & UAN" },
  { seg: "salary", label: "Salary details" },
  { seg: "email", label: "Email addresses" },
  { seg: "bank", label: "Salary bank" },
  { seg: "financials", label: "Financials (Account Aggregator)" },
  { seg: "co-applicant", label: "Co-applicant" },
  { seg: "address-proof", label: "Address proof" },
  { seg: "review", label: "Review & submit" },
];

export default function SignupLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const seg = pathname.split("/").filter(Boolean).pop() ?? "pan";
  const idx = Math.max(0, STEPS.findIndex((s) => s.seg === seg));
  const step = STEPS[idx] ?? STEPS[0];
  const pct = Math.round(((idx + 1) / STEPS.length) * 100);

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
            <Link href="/login" className="text-sm text-muted hover:text-navy">
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
