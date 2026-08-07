"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { MapPin, Loader2, Navigation } from "lucide-react";
import { Input } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { saveProfileSlice, useSavedProfile } from "@/lib/onboarding";
import { useOffer, completeOfferStep, nextOfferRoute, prevOfferRoute } from "@/lib/offer";
import { verificationApi, type StepResult } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";

/**
 * Screen 7: geo address verification. Moved from `/signup/address`. The typed-address fallback still
 * hydrates from the saved profile rather than the zustand draft, so it survives a device change.
 */
export default function LoanAddressPage() {
  const router = useRouter();
  const { appId } = useOffer();
  const saved = useSavedProfile(appId);
  const [mode, setMode] = React.useState<"geo" | "manual">("geo");
  const [manual, setManual] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (saved?.address) setManual(saved.address);
  }, [saved]);

  // Never blocks: a failed address surfaces on the staff Verification Dashboard, not here
  // (revamp.md decision 11), so REVIEW moves on exactly as PASS does.
  const finish = (r: StepResult) => {
    setResult(r);
    if (appId != null && (r.status === "PASS" || r.status === "REVIEW")) {
      void completeOfferStep(appId, "OFFER_ADDRESS", router, nextOfferRoute("address"));
    }
  };

  const verifyCoords = async (latitude: number, longitude: number) => {
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      const r = await verificationApi.address(appId, { latitude, longitude });
      const resolved = typeof r.derived?.address === "string" ? (r.derived.address as string) : undefined;
      if (resolved) await saveProfileSlice(appId, { address: resolved });
      finish(r);
    } catch (err) {
      setError(formatApiError(err, "Could not verify your location."));
    } finally {
      setBusy(false);
    }
  };

  const useMyLocation = () => {
    setError(undefined);
    if (typeof navigator === "undefined" || !navigator.geolocation) {
      setMode("manual");
      setError("Location isn't available on this device — please enter your address.");
      return;
    }
    setBusy(true);
    navigator.geolocation.getCurrentPosition(
      (pos) => { void verifyCoords(pos.coords.latitude, pos.coords.longitude); },
      (err) => {
        setBusy(false);
        setMode("manual");
        setError(
          err.code === err.PERMISSION_DENIED
            ? "Location permission is blocked — allow it in your browser's site settings, or enter your address below."
            : err.code === err.TIMEOUT
              ? "Getting your location took too long — please enter your address instead."
              : "We couldn't access your location — please enter your address instead.",
        );
      },
      { enableHighAccuracy: true, timeout: 10_000, maximumAge: 0 },
    );
  };

  const submitManual = async (e: React.FormEvent) => {
    e.preventDefault();
    if (manual.trim().length < 8) { setTouched(true); return; }
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      await saveProfileSlice(appId, { address: manual.trim() });
      const r = await verificationApi.address(appId, { manualAddress: manual.trim() });
      finish(r);
    } catch (err) {
      setError(formatApiError(err, "Could not verify your address."));
    } finally {
      setBusy(false);
    }
  };

  if (mode === "geo") {
    return (
      <div>
        <div className="form-card">
          <p className="lead mb-5">
            Confirm where you currently live. Sharing your location is the fastest way — we only use it to
            verify your address, never to track you.
          </p>
          <div className="rounded border border-line bg-grey-100 p-7 text-center">
            <span className="mx-auto mb-3 grid h-14 w-14 place-items-center rounded-full bg-navy-tint text-navy">
              <MapPin size={28} />
            </span>
            <h3 className="font-serif text-lg text-navy">Verify your current address</h3>
            <p className="mx-auto mb-5 max-w-sm text-sm text-muted">
              We&apos;ll match your live location to your address records.
            </p>
            <button type="button" onClick={useMyLocation} disabled={busy} className="btn btn-navy">
              {busy ? <Loader2 size={16} className="animate-spin" /> : <Navigation size={16} />}
              {busy ? "Getting your location…" : "Use my current location"}
            </button>
            <div className="mt-4">
              <button type="button" onClick={() => setMode("manual")} className="text-sm font-semibold text-navy hover:underline">
                Enter address manually
              </button>
            </div>
          </div>
          <StepResultBanner result={result} />
          {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
        </div>
        <div className="mt-8">
          <a href={prevOfferRoute("address")} className="btn btn-outline btn-sm">Back</a>
        </div>
        <Reassurance />
      </div>
    );
  }

  return (
    <form onSubmit={submitManual} noValidate>
      <div className="form-card">
        <p className="lead mb-4">Enter your current residential address.</p>
        <Input
          label="Full address"
          required
          value={manual}
          onChange={(e) => { setManual(e.target.value); if (error) setError(undefined); }}
          placeholder="Flat / house, street, area, city, PIN"
          leftIcon={<MapPin size={16} />}
          autoComplete="street-address"
          error={touched && manual.trim().length < 8 ? "Enter your complete address" : undefined}
        />
        <button type="button" onClick={() => { setMode("geo"); setResult(null); setError(undefined); }} className="text-sm font-semibold text-navy hover:underline">
          Use my location instead
        </button>
        <StepResultBanner result={result} />
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions backHref={prevOfferRoute("address")} submit continueLabel={result?.status === "FAIL" ? "Try again" : "Continue"} loading={busy} disabled={busy} />
      <Reassurance />
    </form>
  );
}
