"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { MapPin, Loader2, Navigation } from "lucide-react";
import { Input } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { useOnboarding, saveProfileSlice, nextAfterStep } from "@/lib/onboarding";
import { verificationApi, ApplicationApiError, type StepResult } from "@/lib/api/applications";

export default function SignupAddressPage() {
  const router = useRouter();
  const { mounted, draft, appId } = useOnboarding();
  const [mode, setMode] = React.useState<"geo" | "manual">("geo");
  const [manual, setManual] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (mounted && draft.address) setManual(draft.address);
  }, [mounted, draft.address]);

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/mobile-otp");
  }, [mounted, appId, router]);

  const finish = (r: StepResult, resolved?: string) => {
    setResult(r);
    if (resolved) {
      draft.patch({ address: resolved });
    }
    if (r.status === "PASS" || r.status === "REVIEW") router.push(nextAfterStep("/signup/digilocker"));
  };

  const verifyCoords = async (latitude: number, longitude: number) => {
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      const r = await verificationApi.address(appId, { latitude, longitude });
      const resolved = typeof r.derived?.address === "string" ? (r.derived.address as string) : undefined;
      if (resolved) await saveProfileSlice(appId, { address: resolved });
      finish(r, resolved);
    } catch (err) {
      setError(err instanceof ApplicationApiError ? `${err.message} (${err.code})` : "Could not verify your location.");
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
      () => {
        setBusy(false);
        setMode("manual");
        setError("We couldn't access your location — please enter your address instead.");
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
    draft.patch({ address: manual.trim() });
    try {
      await saveProfileSlice(appId, { address: manual.trim() });
      const r = await verificationApi.address(appId, { manualAddress: manual.trim() });
      finish(r, manual.trim());
    } catch (err) {
      setError(err instanceof ApplicationApiError ? `${err.message} (${err.code})` : "Could not verify your address.");
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
          <a href="/signup/email" className="btn btn-outline btn-sm">Back</a>
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
          onChange={(e) => setManual(e.target.value)}
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
      <WizardActions backHref="/signup/email" submit continueLabel={result?.status === "FAIL" ? "Try again" : "Continue"} loading={busy} disabled={busy} />
      <Reassurance />
    </form>
  );
}
