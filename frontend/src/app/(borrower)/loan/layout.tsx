"use client";

import * as React from "react";
import { usePathname } from "next/navigation";
import { ShieldCheck } from "lucide-react";
import { OFFER_SEGMENTS, useOfferGuard, useOfferSteps } from "@/lib/offer";
import { readStoredAppId } from "@/lib/api/live-journey";

/**
 * Wizard chrome for the post-sanction offer journey (revamp.md Phase 3) — the same progress
 * treatment as the intake, so the borrower reads the two halves as one product.
 *
 * <p>Only the offer routes get the chrome. `/loan/status` (and the retired `apply` / `salary`
 * redirects) live under the same segment but are not steps, so they render bare.
 *
 * <p>The step count is the server's, not a constant: a returning borrower's journey is genuinely
 * shorter because their DigiLocker, references, selfie and address carried over (V47), and telling
 * them "step 1 of 11" when they will walk seven would be a lie the progress bar then repeats.
 */
export default function LoanLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const seg = pathname.split("/").filter(Boolean).pop() ?? "";
  const isOfferStep = OFFER_SEGMENTS.has(seg);
  const steps = useOfferSteps();

  const [appId, setAppId] = React.useState<number | null>(null);
  React.useEffect(() => setAppId(readStoredAppId()), []);
  // Resume is server-side (revamp.md C1): a borrower who left at the selfie comes back to the selfie.
  useOfferGuard(isOfferStep ? appId : null);

  if (!isOfferStep) return <>{children}</>;

  const idx = steps.findIndex((s) => s.seg === seg);
  const step = steps[idx];
  // A step outside this borrower's list (mid-navigation, before the server's list lands) has no
  // number to show; render the chrome without a count rather than "step 0".
  if (!step) return <div className="bg-ivory"><div className="container max-w-content py-10">{children}</div></div>;
  const pct = Math.round(((idx + 1) / steps.length) * 100);

  return (
    <div className="bg-ivory">
      <div className="border-b border-line bg-white">
        <div className="container py-5">
          <div className="mb-2 flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-gold-dark">
              Step {idx + 1} of {steps.length}
            </span>
            <span className="flex items-center gap-1.5 text-xs text-muted">
              <ShieldCheck size={14} className="text-success-600" /> Secure &amp; encrypted
            </span>
          </div>
          <div className="mb-2 flex items-baseline justify-between gap-3">
            <h2 className="font-serif text-xl text-navy">{step.label}</h2>
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
