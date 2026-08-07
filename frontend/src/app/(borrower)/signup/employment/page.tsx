"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Select } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { useOnboarding, saveProfileSlice, completeStep, useSavedProfile } from "@/lib/onboarding";
import { formatApiError } from "@/lib/api/errors";

export default function SignupEmploymentPage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const saved = useSavedProfile(appId);
  const [status, setStatus] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (saved?.employmentStatus) setStatus(saved.employmentStatus);
  }, [saved]);

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/start");
  }, [mounted, appId, router]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!status) {
      setTouched(true);
      return;
    }
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      await saveProfileSlice(appId, { employmentStatus: status });
      await completeStep(appId, "EMPLOYMENT", router, "/signup/set-password");
    } catch (err) {
      setError(formatApiError(err, "Could not save — please try again."));
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">What is your employment status?</p>
        <Select
          label="Employment status"
          required
          value={status}
          onChange={(e) => setStatus(e.target.value)}
          error={touched && !status ? "Select your employment status" : undefined}
        >
          <option value="">Select…</option>
          <option value="SALARIED">Salaried</option>
          <option value="SELF_EMPLOYED">Self-employed</option>
        </Select>
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions backHref="/signup/otp" submit loading={busy} disabled={busy} />
      <Reassurance />
    </form>
  );
}
