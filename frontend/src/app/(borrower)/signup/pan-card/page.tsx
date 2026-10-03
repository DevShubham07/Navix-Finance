"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { CreditCard } from "lucide-react";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { CardSidesUpload, useCardSides, type CardSide, type CardSideSpec } from "@/components/borrower/card-sides-upload";
import { useOnboarding, completeStep, useSavedProfile } from "@/lib/onboarding";
import { formatApiError } from "@/lib/api/errors";

const SIDES: Record<CardSide, CardSideSpec> = {
  front: { docType: "PAN_CARD_FRONT", label: "Front of PAN card" },
  back: { docType: "PAN_CARD_BACK", label: "Back of PAN card" },
};

/**
 * Screen 10 (V75) — both sides of the PAN card. The PAN itself was typed on screen 1 and is
 * verified against the provider on the consent step; this is the physical evidence the credit
 * team compares it with. Mandatory, including the back: submit-kyc refuses without both images.
 */
export default function SignupPanCardPage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const saved = useSavedProfile(appId);
  const sides = useCardSides(appId, SIDES);
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/start");
  }, [mounted, appId, router]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!sides.ready) {
      setTouched(true);
      return;
    }
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    sides.setError(undefined);
    try {
      await sides.upload();
      await completeStep(appId, "PAN_CARD", router, "/signup/consent");
    } catch (err) {
      setError(formatApiError(err, "Could not upload your PAN card — please try again."));
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-1">Upload a clear photo or PDF of both sides of your PAN card.</p>
        {saved?.pan ? (
          <p className="mb-4 flex items-center gap-2 text-sm text-muted">
            <CreditCard size={16} className="text-navy" />
            The card should match the PAN you entered:{" "}
            <strong className="font-mono text-ink">{saved.pan}</strong>
          </p>
        ) : (
          <p className="mb-4 text-sm text-muted">The card should match the PAN you entered at the start.</p>
        )}

        <CardSidesUpload state={sides} spec={SIDES} cardName="PAN card" disabled={busy} touched={touched} />

        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions backHref="/signup/aadhaar" submit loading={busy} disabled={busy || sides.loadingExisting} />
      <Reassurance />
    </form>
  );
}
