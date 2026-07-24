"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { Mail, Building2, Gift } from "lucide-react";
import { Input } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { useOnboarding, saveProfileSlice, nextAfterStep } from "@/lib/onboarding";
import { updateBorrowerName } from "@/lib/api/live-journey";
import { verificationApi, referralApi, type StepResult } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default function SignupEmailPage() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const { mounted, draft, appId } = useOnboarding();
  const [fullName, setFullName] = React.useState("");
  const [employer, setEmployer] = React.useState("");
  const [personalEmail, setPersonalEmail] = React.useState("");
  const [officialEmail, setOfficialEmail] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();
  // Refer-a-friend (optional). Shown only when the program is enabled (config-driven); the reward
  // amount follows the SSM config. The code is applied best-effort on submit and never blocks signup.
  const [referralCode, setReferralCode] = React.useState("");
  const [referralEnabled, setReferralEnabled] = React.useState(true);
  const [rewardRupees, setRewardRupees] = React.useState(200);
  const [referralNote, setReferralNote] = React.useState<{ ok: boolean; text: string } | null>(null);

  React.useEffect(() => {
    if (!mounted) return;
    setFullName(draft.fullName);
    setEmployer(draft.employer);
    setPersonalEmail(draft.personalEmail);
    setOfficialEmail(draft.officialEmail);
    setReferralCode(draft.referralCode);
  }, [mounted]); // eslint-disable-line react-hooks/exhaustive-deps

  // Resolve the program toggle + reward amount (config-driven) for the copy below.
  React.useEffect(() => {
    let active = true;
    referralApi
      .me()
      .then((r) => {
        if (active) {
          setReferralEnabled(r.enabled);
          setRewardRupees(r.rewardRupees);
        }
      })
      .catch(() => {
        /* referral is optional — leave defaults / hide on failure is not needed */
      });
    return () => {
      active = false;
    };
  }, []);

  /** Preview the typed code (live feedback); never throws. */
  const checkReferral = async () => {
    const code = referralCode.trim();
    if (!code) {
      setReferralNote(null);
      return;
    }
    try {
      const r = await referralApi.validate(code);
      setReferralNote({ ok: r.valid, text: r.message });
    } catch {
      setReferralNote(null);
    }
  };

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/mobile-otp");
  }, [mounted, appId, router]);

  const nameOk = fullName.trim().length > 2;
  const employerOk = employer.trim().length > 1;
  const officialOk = EMAIL_RE.test(officialEmail);
  const personalOk = personalEmail === "" || EMAIL_RE.test(personalEmail);
  const formOk = nameOk && employerOk && officialOk && personalOk;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formOk) { setTouched(true); return; }
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    draft.patch({ fullName: fullName.trim(), employer: employer.trim(), personalEmail: personalEmail.trim(), officialEmail: officialEmail.trim(), referralCode: referralCode.trim() });
    // Contact email for notifications (sanction letter + statements): prefer the personal
    // inbox the borrower actually reads, fall back to the verified work email.
    const contactEmail = (personalEmail.trim() || officialEmail.trim());
    try {
      await saveProfileSlice(appId, { fullName: fullName.trim(), employer: employer.trim(), employmentStatus: "SALARIED", email: contactEmail });
      // Map the typed name onto the live session so the header/dashboard greet them by name.
      await updateBorrowerName(fullName.trim());
      queryClient.invalidateQueries({ queryKey: ["borrower-me"] });
      const r = await verificationApi.email(appId, officialEmail.trim());
      setResult(r);
      if (r.status === "PASS" || r.status === "REVIEW") {
        // Apply the referral code best-effort — a bad/duplicate code must never block onboarding.
        if (referralEnabled && referralCode.trim()) {
          try {
            await referralApi.apply(referralCode.trim());
          } catch {
            /* non-blocking */
          }
        }
        router.push(nextAfterStep("/signup/address"));
      }
    } catch (err) {
      setError(formatApiError(err, "Could not verify your email — please try again."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">
          We send your sanction letter and statements to your personal email, and confirm your employer from your
          official one.
        </p>
        <Input
          label="Full name (as per PAN)"
          required
          value={fullName}
          onChange={(e) => setFullName(e.target.value)}
          placeholder="Aarav Sharma"
          autoComplete="name"
          error={touched && !nameOk ? "Enter your name as printed on your PAN" : undefined}
        />
        <Input
          label="Employer name"
          required
          value={employer}
          onChange={(e) => setEmployer(e.target.value)}
          placeholder="Infosys Limited"
          leftIcon={<Building2 size={16} />}
          autoComplete="organization"
          error={touched && !employerOk ? "Enter your employer's name" : undefined}
        />
        <Input
          label="Personal email"
          type="email"
          value={personalEmail}
          onChange={(e) => setPersonalEmail(e.target.value)}
          placeholder="you@example.com"
          leftIcon={<Mail size={16} />}
          autoComplete="email"
          error={touched && !personalOk ? "Enter a valid email address" : undefined}
        />
        <Input
          label="Official / work email"
          required
          type="email"
          value={officialEmail}
          onChange={(e) => setOfficialEmail(e.target.value)}
          placeholder="you@company.com"
          leftIcon={<Mail size={16} />}
          helperText="Used to confirm your employer. We never email your workplace."
          error={touched && !officialOk ? "Enter your valid work email" : undefined}
        />
        {referralEnabled ? (
          <>
            <Input
              label="Referral code (optional)"
              value={referralCode}
              onChange={(e) => {
                setReferralCode(e.target.value.toUpperCase());
                setReferralNote(null);
              }}
              onBlur={checkReferral}
              placeholder="e.g. DHANBOOST123"
              leftIcon={<Gift size={16} />}
              autoCapitalize="characters"
              helperText={`You and your friend each get ₹${rewardRupees} once your first loan is disbursed.`}
            />
            {referralNote ? (
              <p className={`-mt-2 mb-3 text-sm ${referralNote.ok ? "text-success-600" : "text-error-600"}`}>
                {referralNote.text}
              </p>
            ) : null}
          </>
        ) : null}
        <StepResultBanner result={result} />
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions backHref="/signup/mobile-otp" submit continueLabel={result?.status === "FAIL" ? "Try again" : "Continue"} loading={busy} disabled={busy} />
      <Reassurance />
    </form>
  );
}
