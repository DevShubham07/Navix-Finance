"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { FileText, Loader2 } from "lucide-react";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { useOnboarding, AGREEMENT_DOCS, AGREEMENT_VERSIONS } from "@/lib/onboarding";
import { verificationApi, ApplicationApiError, type StepResult } from "@/lib/api/applications";

export default function SignupAgreementPage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const [texts, setTexts] = React.useState<Record<string, string>>({});
  const [consent, setConsent] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/mobile-otp");
  }, [mounted, appId, router]);

  React.useEffect(() => {
    let cancelled = false;
    (async () => {
      const entries = await Promise.all(
        AGREEMENT_DOCS.map(async (d) => {
          try {
            const res = await fetch(d.href, { cache: "no-store" });
            return [d.key, res.ok ? await res.text() : "Could not load this document."] as const;
          } catch {
            return [d.key, "Could not load this document."] as const;
          }
        }),
      );
      if (!cancelled) setTexts(Object.fromEntries(entries));
    })();
    return () => { cancelled = true; };
  }, []);

  const submit = async () => {
    if (!consent || appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      const r = await verificationApi.agreement(appId, AGREEMENT_VERSIONS);
      setResult(r);
      if (r.status === "PASS" || r.status === "REVIEW") router.push("/signup/review");
    } catch (err) {
      setError(err instanceof ApplicationApiError ? `${err.message} (${err.code})` : "Could not record your consent — please try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <div className="form-card">
        <p className="lead mb-5">
          Please read your loan documents in full. You must accept all three to continue — these are the binding
          terms of your advance.
        </p>

        <div className="grid gap-4">
          {AGREEMENT_DOCS.map((d) => (
            <div key={d.key} className="overflow-hidden rounded border border-line">
              <div className="flex items-center gap-2 border-b border-line bg-grey-100 px-4 py-2.5 text-sm font-semibold text-navy">
                <FileText size={16} /> {d.title}
              </div>
              <div className="max-h-56 overflow-y-auto whitespace-pre-wrap bg-white px-4 py-3 text-xs leading-relaxed text-ink">
                {texts[d.key] ?? (
                  <span className="flex items-center gap-2 text-muted">
                    <Loader2 size={14} className="animate-spin" /> Loading…
                  </span>
                )}
              </div>
            </div>
          ))}
        </div>

        <label className="checkbox mt-5">
          <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
          <span>I have read and agree to all three documents above.</span>
        </label>

        <StepResultBanner result={result} />
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions backHref="/signup/selfie" continueLabel="Accept & continue" onContinue={submit} loading={busy} disabled={!consent || busy} />
      <Reassurance />
    </div>
  );
}
