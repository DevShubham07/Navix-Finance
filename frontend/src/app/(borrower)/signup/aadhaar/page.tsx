"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Fingerprint } from "lucide-react";
import { Input } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { CardSidesUpload, useCardSides, type CardSide, type CardSideSpec } from "@/components/borrower/card-sides-upload";
import { useOnboarding, completeStep, saveProfileSlice, useSavedProfile } from "@/lib/onboarding";
import { formatApiError } from "@/lib/api/errors";
import { formatAadhaar, isValidAadhaar, normalizeAadhaar } from "@/lib/aadhaar";

/** Document types for this screen — distinct from the DigiLocker-fallback AADHAAR_FRONT/BACK pair. */
const SIDES: Record<CardSide, CardSideSpec> = {
  front: { docType: "AADHAAR_CARD_FRONT", label: "Front of Aadhaar card" },
  back: { docType: "AADHAAR_CARD_BACK", label: "Back of Aadhaar card" },
};

/**
 * Screen 9 (V75) — the Aadhaar number, typed and Verhoeff-checked, plus both sides of the card.
 * Mandatory: the backend holds the borrower here (`JourneyService.derive`) and refuses submit-kyc
 * until the number and both images are on file. The number is later cross-checked against the
 * masked Aadhaar the PAN record and DigiLocker return; a mismatch rejects the application.
 */
export default function SignupAadhaarPage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const saved = useSavedProfile(appId);
  const sides = useCardSides(appId, SIDES);
  const [digits, setDigits] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/start");
  }, [mounted, appId, router]);

  // Resume: the number already saved on the profile (another device, or a reload).
  React.useEffect(() => {
    if (saved?.aadhaar && !digits) setDigits(normalizeAadhaar(saved.aadhaar));
  }, [saved?.aadhaar]); // eslint-disable-line react-hooks/exhaustive-deps

  const numberOk = isValidAadhaar(digits);
  const formOk = numberOk && sides.ready;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formOk) {
      setTouched(true);
      return;
    }
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    sides.setError(undefined);
    try {
      if (digits !== normalizeAadhaar(saved?.aadhaar ?? "")) {
        await saveProfileSlice(appId, { aadhaar: digits });
      }
      await sides.upload();
      await completeStep(appId, "AADHAAR", router, "/signup/pan-card");
    } catch (err) {
      setError(formatApiError(err, "Could not save your Aadhaar details — please try again."));
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">
          Enter your Aadhaar number exactly as printed on the card, then upload a clear photo or PDF of
          both sides.
        </p>
        <Input
          label="Aadhaar number"
          required
          inputMode="numeric"
          autoComplete="off"
          value={formatAadhaar(digits)}
          onChange={(e) => {
            setDigits(normalizeAadhaar(e.target.value));
            setError(undefined);
          }}
          placeholder="1234 5678 9012"
          maxLength={14}
          leftIcon={<Fingerprint size={16} />}
          disabled={busy}
          error={
            touched && !numberOk
              ? digits.length === 12
                ? "That doesn't look like a valid Aadhaar number — please check it against your card."
                : "Enter your 12-digit Aadhaar number."
              : undefined
          }
          helperText="12 digits. We compare it with the Aadhaar linked to your PAN and DigiLocker."
        />

        <CardSidesUpload state={sides} spec={SIDES} cardName="Aadhaar card" disabled={busy} touched={touched} />

        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions backHref="/signup/payslips" submit loading={busy} disabled={busy || sides.loadingExisting} />
      <Reassurance />
    </form>
  );
}
